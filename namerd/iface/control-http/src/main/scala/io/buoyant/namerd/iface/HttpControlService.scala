package io.buoyant.namerd.iface

import com.fasterxml.jackson.databind._
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.google.common.cache.{CacheBuilder, CacheLoader, RemovalListener, RemovalNotification}
import com.twitter.finagle.http._
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.util.{Drv, Rng}
import com.twitter.finagle.{Status => _, _}
import com.twitter.io.Buf
import com.twitter.util._
import io.buoyant.admin.names.DelegateApiHandler
import io.buoyant.admin.names.DelegateApiHandler.{JsonDelegateTree, Addr => JsonAddr}
import io.buoyant.namer.{DelegateTree, Delegator, EnumeratingNamer}
import io.buoyant.namerd.DtabStore.{DtabNamespaceDoesNotExistException, DtabVersionMismatchException, Forbidden}
import io.buoyant.namerd.{DtabStore, Ns, RichActivity, VersionedDtab, DtabCodec => DtabModule}

object HttpControlService {

  object Json {

    private[this] val mapper = new ObjectMapper with ScalaObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.registerModule(DtabModule.module)

    def read[T: Manifest](buf: Buf): Try[T] = {
      val Buf.ByteBuffer.Owned(bb) = Buf.ByteBuffer.coerce(buf)
      Try { mapper.readValue[T](bb.array) }
    }

    def write[T](t: T): Buf =
      Buf.ByteArray.Owned(mapper.writeValueAsBytes(t))
  }

  sealed trait DtabCodec {
    def contentTypes: Set[String]
    def write(dtab: Dtab): Buf
    def read(buf: Buf): Try[Dtab]
  }
  object DtabCodec {

    object Json extends DtabCodec {
      val contentTypes = Set(MediaType.Json)
      def write(dtab: Dtab) = HttpControlService.Json.write(dtab)
      def read(buf: Buf) =
        HttpControlService.Json.read[IndexedSeq[Dentry]](buf).map(Dtab(_))
    }

    object Text extends DtabCodec {
      val contentTypes = Set("application/dtab", MediaType.Txt)
      def write(dtab: Dtab) = Buf.Utf8(dtab.show)
      def read(buf: Buf) = {
        val Buf.Utf8(d) = buf
        Try { Dtab.read(d) }
      }
    }

    def accept(types: Seq[String]): Option[(String, DtabCodec)] =
      types.map(_.toLowerCase).foldLeft[Option[(String, DtabCodec)]](None) {
        case (None, ct) => byContentType(ct).map(ct -> _)
        case (t, _) => t
      }

    def byContentType(ct: String): Option[DtabCodec] =
      if (Json.contentTypes(ct)) Some(DtabCodec.Json)
      else if (Text.contentTypes(ct)) Some(DtabCodec.Text)
      else None

    val default = (MediaType.Json, Json)
  }

  val apiPrefix = "/api/1"

  object DtabUri {
    val prefix = s"$apiPrefix/dtabs"
    val prefixSlash = s"$prefix/"

    def unapply(request: Request): Option[(Method, Option[Ns])] =
      if (request.path == prefix) Some(request.method, None)
      else if (!request.path.startsWith(prefixSlash)) None
      else request.path.stripPrefix(prefixSlash) match {
        case "" => Some(request.method, None)
        case ns => Some(request.method, Some(ns))
      }
  }

  case class InvalidPathException(path: String, underlying: Exception)
    extends Exception(s"Invalid path: $path / ${underlying.getMessage}", underlying)

  trait NsPathUri {
    val prefix: String
    lazy val prefixSlash = s"$prefix/"

    def unapply(request: Request): Option[(Option[Ns], Path)] = {
      if (request.path.startsWith(prefix)) {
        val ns = if (request.path.startsWith(prefixSlash)) {
          Some(request.path.stripPrefix(prefixSlash))
        } else {
          None
        }
        val path = try {
          Path.read(request.getParam("path"))
        } catch {
          case e: IllegalArgumentException =>
            throw InvalidPathException(request.getParam("path"), e)
        }
        Some(ns, path)
      } else {
        None
      }
    }
  }

  object BindUri extends NsPathUri {
    val prefix = s"$apiPrefix/bind"
  }

  object AddrUri extends NsPathUri {
    val prefix = s"$apiPrefix/addr"
  }

  object ResolveUri extends NsPathUri {
    val prefix = s"$apiPrefix/resolve"
  }

  object DelegateUri extends NsPathUri {
    val prefix = s"$apiPrefix/delegate"
  }

  def versionString(buf: Buf): String = {
    val versionBytes = new Array[Byte](buf.length)
    buf.write(versionBytes, 0)
    Base64StringEncoder.encode(versionBytes)
  }

  private[iface] val newline = Buf.Utf8("\n")

  private val DefaultNamer: (Path, Namer) = Path.empty -> Namer.global
}

class HttpControlService(storage: DtabStore, delegate: Ns => NameInterpreter, namers: Map[Path, Namer])
  extends Service[Request, Response] {
  import HttpControlService._

  // cached activities are not held open so there is very little cost to caching
  // them forever
  private[this] val bindingCacheSize = 100000
  private[this] val addrCacheSize = 100000

  /** Get the dtab, if it exists. */
  private[this] def getDtab(ns: String): Future[Option[VersionedDtab]] =
    storage.observe(ns).toFuture

  private[this] val delegateApiHander = new DelegateApiHandler(delegate, namers.toSeq)

  def apply(req: Request): Future[Response] = Future(req match {
    case DtabUri(_, None) =>
      handleList(req)
    case DtabUri(Method.Head, Some(ns)) =>
      handleHeadDtab(ns, req)
    case DtabUri(Method.Get, Some(ns)) =>
      handleGetDtab(ns, req)
    case DtabUri(Method.Put, Some(ns)) =>
      handlePutDtab(ns, req)
    case DtabUri(Method.Post, Some(ns)) =>
      handlePostDtab(ns, req)
    case DtabUri(Method.Delete, Some(ns)) =>
      handleDeleteDtab(ns)
    case BindUri(Some(ns), path) =>
      handleGetBind(ns, path, req)
    case AddrUri(Some(ns), path) =>
      handleGetAddr(ns, path, req)
    case ResolveUri(Some(ns), path) =>
      handleGetResolve(ns, path, req)
    case DelegateUri(Some(ns), path) =>
      handleGetDelegate(ns, path, req.params.get("dtab"))
    case DelegateUri(None, path) =>
      delegateApiHander(req)
    case _ if req.path == s"$apiPrefix/bound-names" && req.method == Method.Get =>
      handleGetBoundNames(req)
    // invalid uri/method
    case _ =>
      Future.value(Response(Status.NotFound))
  }).flatten.handle {
    case Forbidden => Response(Status.Forbidden)
    case ex@InvalidPathException(path, _) =>
      val resp = Response(Status.BadRequest)
      resp.contentString = ex.getMessage
      resp
  }

  private[this] def streamingRespF[T](
    values: Event[Try[T]],
    contentType: Option[String] = None
  )(render: T => Future[Buf]): Future[Response] = {
    val resp = Response()
    for (ct <- contentType) resp.contentType = ct
    resp.setChunked(true)
    val writer = resp.writer
    // closable is a handle to the values observation so that we can close the observation when the
    // streaming connection is terminated
    @volatile var closable: Closable = Closable.nop
    // calls to writer.write must be flatMapped together to ensure proper ordering and backpressure
    // writeFuture is an accumulator of those flatMapped Futures
    @volatile var writeFuture: Future[Unit] = Future.Unit
    closable = values.dedup.respond {
      case Return(t) =>
        writeFuture = writeFuture.before {
          render(t).flatMap {
            case Buf.Empty => Future.Unit
            case buf =>
              writer.write(buf).onFailure { _ =>
                val _ = closable.close()
              }
          }
        }
      case Throw(e) =>
        val _ = writer.write(Buf.Utf8(e.getMessage).concat(newline)).before(writer.close())
        val __ = closable.close() // https://issues.scala-lang.org/browse/SI-7691
    }
    Future.value(resp)
  }

  private[this] def streamingResp[T](
    values: Event[Try[T]],
    contentType: Option[String] = None
  )(render: T => Buf): Future[Response] =
    streamingRespF(values, contentType) { t =>
      Future.value(render(t))
    }

  private[this] def isStreaming(req: Request): Boolean = req.getBooleanParam("watch")

  private[this] def renderList(list: Iterable[Ns]): Buf = Json.write(list).concat(newline)

  private[this] def handleList(req: Request): Future[Response] =
    if (isStreaming(req)) {
      streamingResp(storage.list().values, Some(MediaType.Json))(renderList)
    } else {
      storage.list().toFuture.map { namespaces =>
        val rsp = Response()
        rsp.contentType = MediaType.Json
        rsp.content = renderList(namespaces.toSeq.sorted)
        rsp
      }
    }

  private[this] def handleGetDtab(ns: String, req: Request): Future[Response] = {
    val (contentType, codec) = DtabCodec.accept(req.accept).getOrElse(DtabCodec.default)
    if (isStreaming(req)) {
      val dtabs: Event[Try[VersionedDtab]] = storage.observe(ns).values.collect {
        case Return(Some(dtab)) => Return(dtab)
        case Throw(e) => Throw(e)
      }
      streamingResp(dtabs, Some(contentType)) { dtab =>
        codec.write(dtab.dtab).concat(newline)
      }
    } else {
      storage.observe(ns).toFuture.map {
        case Some(dtab) =>
          val rsp = Response()
          rsp.contentType = contentType
          rsp.headerMap.add(Fields.Etag, versionString(dtab.version))
          rsp.content = codec.write(dtab.dtab).concat(newline)
          rsp

        case None => Response(Status.NotFound)
      }
    }
  }

  private[this] def handleHeadDtab(ns: String, req: Request): Future[Response] =
    getDtab(ns).map {
      case Some(dtab) =>
        val rsp = Response()
        rsp.headerMap.add(Fields.Etag, versionString(dtab.version))
        rsp

      case None => Response(Status.NotFound)
    }

  private[this] def handlePutDtab(ns: String, req: Request): Future[Response] =
    req.contentType.flatMap(DtabCodec.byContentType) match {
      case Some(codec) =>
        codec.read(req.content) match {
          case Return(dtab) =>
            req.headerMap.get(Fields.IfMatch) match {
              case Some(stamp) =>
                val buf = Buf.ByteArray.Owned(Base64StringEncoder.decode(stamp))
                storage.update(ns, dtab, buf).transform {
                  case Return(_) =>
                    Future.value(Response(Status.NoContent))
                  case Throw(e: DtabNamespaceDoesNotExistException) =>
                    Future.value(Response(Status.NotFound))
                  case Throw(e: DtabVersionMismatchException) =>
                    Future.value(Response(Status.PreconditionFailed))
                  case Throw(_) =>
                    Future.value(Response(Status.InternalServerError))
                }
              case None =>
                storage.put(ns, dtab).map { _ =>
                  Response(Status.NoContent)
                }
            }

          // invalid dtab
          case Throw(_) => Future.value(Response(Status.BadRequest))
        }

      // invalid content type
      case None => Future.value(Response(Status.BadRequest))
    }

  private[this] def handlePostDtab(ns: String, req: Request): Future[Response] =
    req.contentType.flatMap(DtabCodec.byContentType) match {
      case Some(codec) =>
        codec.read(req.content) match {
          case Return(dtab) =>
            storage.create(ns, dtab).transform {
              case Return(_) => Future.value(Response(Status.NoContent))
              case Throw(_) => Future.value(Response(Status.InternalServerError))
            }

          // invalid dtab
          case Throw(_) => Future.value(Response(Status.BadRequest))
        }

      // invalid content type
      case None => Future.value(Response(Status.BadRequest))
    }

  private[this] def handleDeleteDtab(ns: String): Future[Response] =
    storage.delete(ns).transform {
      case Return(()) => Future.value(Response(Status.NoContent))
      case Throw(_: DtabNamespaceDoesNotExistException) => Future.value(Response(Status.NotFound))
      case Throw(_) => Future.value(Response(Status.InternalServerError))
    }

  private[this] def getBind(ns: String, path: Path, extraDtab: Option[String]): Activity[NameTree[Name.Bound]] =
    extraDtab match {
      case Some(dtab) => delegate(ns).bind(Dtab.read(dtab), path)
      case _ => bindingCache.get(NsPath(ns, path)).activity
    }


  case class NsPath(ns: Ns, path: Path)
  case class ActClose(activity: Activity[NameTree[Name.Bound]], closable: Closable)

  private[this] val bindingCache = CacheBuilder.newBuilder()
    .maximumSize(bindingCacheSize)
    .removalListener(new RemovalListener[NsPath, ActClose] {
      override def onRemoval(notification: RemovalNotification[NsPath, ActClose]): Unit = {
        val _ = notification.getValue.closable.close()
      }
    })
    .build[NsPath, ActClose](
      new CacheLoader[NsPath, ActClose] {
        override def load(key: NsPath): ActClose = {
          val act = delegate(key.ns).bind(Dtab.empty, key.path)
          // we want to keep the activity observed as long as it's in the cache
          val closable = act.run.changes.respond(_ => ())
          ActClose(act, closable)
        }
      }
    )

  private[this] def renderNameTree(tree: NameTree[Name.Bound]): Future[Buf] =
    JsonDelegateTree.mk(
      DelegateTree.fromNameTree(tree)
    ).map(DelegateApiHandler.Codec.writeBuf).map(_.concat(Buf.Utf8("\n")))

  private[this] def handleGetBind(ns: String, path: Path, req: Request): Future[Response] = {
    val extraDtab = req.params.get("dtab")
    if (isStreaming(req)) {
      streamingRespF(getBind(ns, path, extraDtab).values)(renderNameTree)
    } else {
      getBind(ns, path, extraDtab).toFuture.flatMap(renderNameTree).map { buf =>
        val rsp = Response()
        rsp.content = buf
        rsp
      }
    }
  }

  private[this] val addrCache = CacheBuilder.newBuilder()
    .maximumSize(addrCacheSize)
    .build[(String, Path), Activity[Addr]](
      new CacheLoader[(String, Path), Activity[Addr]] {
        override def load(key: (String, Path)): Activity[Addr] =
          bindAddrId(key._2).flatMap {
            case NameTree.Leaf(bound) => Activity(bound.addr.map(Activity.Ok(_)))
            case NameTree.Empty => Activity.value(Addr.Bound())
            case NameTree.Fail => Activity.exception(new Exception("name tree failed"))
            case NameTree.Neg => Activity.value(Addr.Neg)
            case NameTree.Alt(_) | NameTree.Union(_) =>
              Activity.exception(new Exception(s"${key._2.show} is not a concrete bound id"))
          }.flatMap {
            case Addr.Pending => Activity.pending
            case addr => Activity.value(addr)
          }
      }
    )

  private[this] def bindAddrId(id: Path): Activity[NameTree[Name.Bound]] = {
    val (pfx, namer) = namers.find { case (p, _) => id.startsWith(p) }.getOrElse(DefaultNamer)
    namer.bind(NameTree.Leaf(id.drop(pfx.size)))
  }

  private[this] def renderAddr(addr: Addr): Buf =
    DelegateApiHandler.Codec.writeBuf(JsonAddr.mk(addr)).concat(Buf.Utf8("\n"))

  private[this] def handleGetAddr(ns: String, path: Path, req: Request): Future[Response] = {
    if (isStreaming(req)) {
      streamingResp(addrCache.get((ns, path)).values)(renderAddr)
    } else {
      addrCache.get((ns, path)).toFuture.map { addr =>
        val rsp = Response()
        rsp.content = renderAddr(addr)
        rsp
      }
    }
  }

  private[this] def handleGetDelegate(ns: String, path: Path, extraDtab: Option[String]): Future[Response] = {
    delegate(ns) match {
      case delegator: Delegator =>
        val fullDtab = extraDtab match {
          case Some(str) => Dtab.read(str)
          case None => Dtab.empty
        }
        DelegateApiHandler.getDelegateRsp(fullDtab.show, path.show, delegator)
      case _ =>
        val rsp = Response(Status.NotImplemented)
        rsp.contentString = s"Name Interpreter for $ns cannot show delegations"
        Future.value(rsp)
    }
  }

  private[this] def flattenTree(tree: NameTree[Name.Bound], rng: Rng = Rng.threadLocal): Option[Path] = {
    tree match {
      case NameTree.Neg | NameTree.Fail | NameTree.Empty | NameTree.Alt() => None
      case NameTree.Leaf(bound) => bound.id match {
        case p: Path => Some(p)
        case _ => None
      }
      case NameTree.Union(weightedTrees@_*) =>
        val (weights, trees) = weightedTrees.unzip { case NameTree.Weighted(w, t) => (w, t) }
        val drv = Drv.fromWeights(weights)
        val randomTree = trees(drv(rng))
        flattenTree(randomTree, rng)
      case NameTree.Alt(alts@_*) =>
        flattenTree(alts.head) match {
          case p: Some[Path] => p
          case None => flattenTree(NameTree.Alt(alts.tail: _*), rng)
        }
    }
  }

  private[this] def handleGetResolve(ns: String, path: Path, req: Request): Future[Response] = {
    val extraDtab = req.params.get("dtab")

    val activity = getBind(ns, path, extraDtab).flatMap { tree =>
      flattenTree(tree) match {
        case Some(p) => addrCache.get((ns, p))
        case None => Activity.value(Addr.Neg)
      }
    }

    if (isStreaming(req)) {
      streamingResp(activity.values, Some(MediaType.Json))(renderAddr)
    } else {
      activity.toFuture.map { addr =>
        // maybe we should return 404 on Addr.Neg and 302 with Retry-After header on Addr.Pending?..
        val rsp = Response()
        rsp.content = renderAddr(addr)
        rsp.contentType = MediaType.Json
        rsp
      }
    }
  }

  private[this] val enumeratingNamers = namers.values.collect {
    case namer: EnumeratingNamer => namer
  }.toSeq
  private[this] val boundNames =
    Activity.collect(enumeratingNamers.map(_.getAllNames))
      .map(_.toSet.flatten)

  private[this] def renderBoundNames(names: Set[Path]): Buf =
    Buf.Utf8(names.map(_.show).mkString("[\"", "\",\"", "\"]\n"))

  private[this] def handleGetBoundNames(req: Request): Future[Response] = {
    if (isStreaming(req)) {
      streamingResp(boundNames.values, Some(MediaType.Json))(renderBoundNames)
    } else {
      boundNames.toFuture.map { names =>
        val rsp = Response()
        rsp.contentType = MediaType.Json
        rsp.content = renderBoundNames(names)
        rsp
      }
    }
  }
}
