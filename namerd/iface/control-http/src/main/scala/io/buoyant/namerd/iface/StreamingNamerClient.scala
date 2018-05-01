package io.buoyant.namerd.iface

import com.fasterxml.jackson.core.`type`.TypeReference
import com.google.common.cache.{CacheBuilder, CacheLoader}
import com.twitter.concurrent.AsyncStream
import com.twitter.finagle._
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.util.{Activity, Closable, Future, Var}
import io.buoyant.admin.DelegationJsonCodec
import io.buoyant.admin.names.DelegateApiHandler
import io.buoyant.admin.names.DelegateApiHandler.JsonDelegateTree
import io.buoyant.config.JsonStreamParser
import io.buoyant.namer.{DelegateTree, Delegator}
import io.buoyant.namerd.{DtabCodec => DtabModule}

/**
 * This NameInterpreter is backed by namerd's HTTP streaming API.
 */
class StreamingNamerClient(
  client: Service[Request, Response],
  namespace: String
) extends NameInterpreter with Delegator {

  /* Public members */

  override def bind(
    dtab: Dtab,
    path: Path
  ): Activity[NameTree[Name.Bound]] = bindCache.get((dtab, path))

  override def delegate(
    dtab: Dtab,
    tree: NameTree[Name.Path]
  ): Future[DelegateTree[Name.Bound]] = {
    val path = tree match {
      case NameTree.Leaf(Name.Path(p)) => p
      case _ => throw new IllegalArgumentException("Delegation too complex")
    }
    getDelegate(dtab, path)
  }

  override def dtab: Activity[Dtab] = watchDtab

  /* private members */

  /* Activities are cached forever.  This is cheap because they are only active
   * while being observed. */

  private[this]type BindKey = (Dtab, Path)

  private[this] val bindCache = CacheBuilder.newBuilder()
    .build[BindKey, Activity[NameTree[Name.Bound]]](
      new CacheLoader[BindKey, Activity[NameTree[Name.Bound]]] {
        def load(key: BindKey): Activity[NameTree[Name.Bound]] = watchName(key._1, key._2)
      }
    )

  private[this] val addrCache = CacheBuilder.newBuilder()
    .build[Path, Var[Addr]](
      new CacheLoader[Path, Var[Addr]] {
        def load(key: Path): Var[Addr] = watchAddr(key)
      }
    )

  private[this] val mapper = DelegationJsonCodec.mapper
  mapper.registerModule(DtabModule.module)
  private[this] val parser = new JsonStreamParser(mapper)

  private[this] implicit val jsonDelegateTreeType = new TypeReference[JsonDelegateTree] {}
  private[this] implicit val addrType = new TypeReference[DelegateApiHandler.Addr] {}
  private[this] implicit val dtabType = new TypeReference[IndexedSeq[Dentry]] {}

  private[this] def watchName(dtab: Dtab, path: Path): Activity[NameTree[Name.Bound]] = {

    @volatile var rspF: Future[Response] = Future.never

    def mkStream(): AsyncStream[Activity.State[NameTree[Name.Bound]]] = {
      val bindReq = Request(
        s"/api/1/bind/$namespace",
        "path" -> path.show,
        "watch" -> "1",
        "dtab" -> dtab.show
      )
      rspF = client(bindReq)
      AsyncStream.fromFuture(rspF).flatMap { rsp =>
        parser.readStream[JsonDelegateTree](rsp.reader)
          .map(JsonDelegateTree.toNameTree)
          .map { tree =>
            tree.map { bound =>
              bound.id match {
                case id: Path => Name.Bound(addrCache.get(id), id, bound.path)
                case _ => bound
              }
            }
          }.map(Activity.Ok(_))
      }
    }

    val state = StreamingNamerClient.asyncStreamToVar(Activity.Pending, mkStream _, Closable.make { _ =>
      rspF.onSuccess { _.reader.discard() }
      rspF.raise(StreamingNamerClient.Closed)
      Future.Unit
    })

    Activity(state)
  }

  private[this] def watchAddr(path: Path): Var[Addr] = {
    @volatile var rspF: Future[Response] = Future.never

    def mkStream(): AsyncStream[Addr] = {
      val addrReq = Request(
        s"/api/1/addr/$namespace",
        "path" -> path.show,
        "watch" -> "1"
      )
      rspF = client(addrReq)
      AsyncStream.fromFuture(rspF).flatMap { rsp =>
        parser.readStream[DelegateApiHandler.Addr](rsp.reader).map(DelegateApiHandler.Addr.toFinagle)
      }
    }

    StreamingNamerClient.asyncStreamToVar(Addr.Pending, mkStream _, Closable.make { _ =>
      rspF.onSuccess { _.reader.discard() }
      rspF.raise(StreamingNamerClient.Closed)
      Future.Unit
    })
  }

  private[this] def getDelegate(dtab: Dtab, path: Path): Future[DelegateTree[Name.Bound]] = {
    @volatile var rspF: Future[Response] = Future.never

    val delegateReq = Request(
      s"/api/1/delegate/$namespace",
      "path" -> path.show,
      "dtab" -> dtab.show
    )

    client(delegateReq).map { rsp =>
      mapper.readValue[JsonDelegateTree](rsp.contentString)
    }.map(JsonDelegateTree.toDelegateTree)
  }

  private[this] lazy val watchDtab: Activity[Dtab] = {
    @volatile var rspF: Future[Response] = Future.never

    def mkStream(): AsyncStream[Activity.State[Dtab]] = {
      val bindReq = Request(
        s"/api/1/dtabs/$namespace",
        "watch" -> "1"
      )
      rspF = client(bindReq)
      AsyncStream.fromFuture(rspF).flatMap { rsp =>
        parser.readStream[IndexedSeq[Dentry]](rsp.reader)
          .map(Dtab(_))
          .map(Activity.Ok(_))
      }
    }

    val state = StreamingNamerClient.asyncStreamToVar(Activity.Pending, mkStream _, Closable.make { _ =>
      rspF.onSuccess { _.reader.discard() }
      rspF.raise(StreamingNamerClient.Closed)
      Future.Unit
    })

    Activity(state)
  }
}

object StreamingNamerClient {
  val Closed = Failure("stream closed", Failure.Interrupted)

  def asyncStreamToVar[T](init: T, mkStream: () => AsyncStream[T], closable: Closable): Var[T] = {
    Var.async[T](init) { update =>

      @volatile var stopped = false

      def loop(stream: AsyncStream[T]): Future[Unit] = {
        if (stopped) {
          Future.Unit
        } else {
          stream.uncons.flatMap {
            case Some((t, next)) =>
              update() = t
              loop(next())
            case None =>
              loop(mkStream())
          }
        }
      }

      loop(mkStream())

      Closable.all(closable, Closable.make { _ =>
        stopped = true
        Future.Unit
      })
    }
  }
}

