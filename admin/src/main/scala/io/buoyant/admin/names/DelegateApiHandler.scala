package io.buoyant
package admin.names

import com.fasterxml.jackson.annotation._
import com.fasterxml.jackson.core.{io => _, _}
import com.fasterxml.jackson.databind._
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.conversions.time._
import com.twitter.finagle.http._
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.util.DefaultTimer
import com.twitter.finagle.{Path, Addr => FAddr, Address => FAddress, Status => _, TimeoutException => _, _}
import com.twitter.io.Buf
import com.twitter.util._
import io.buoyant.namer._
import java.net.InetSocketAddress

object DelegateApiHandler {

  private def err(status: Status) = Future.value(Response(status))

  private def err(status: Status, content: String) = {
    val resp = Response(status)
    resp.contentString = content
    Future.value(resp)
  }

  case class Address(ip: String, port: Int, meta: Map[String, Any])
  object Address {
    def mk(addr: FAddress): Option[Address] = addr match {
      case FAddress.Inet(isa, meta) => Some(Address(isa.getAddress.getHostAddress, isa.getPort, meta))
      case _ => None
    }

    def toFinagle(addr: Address): FAddress =
      FAddress.Inet(new InetSocketAddress(addr.ip, addr.port), addr.meta)
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
  @JsonSubTypes(Array(
    new JsonSubTypes.Type(value = classOf[Addr.Bound], name = "bound"),
    new JsonSubTypes.Type(value = classOf[Addr.Failed], name = "failed"),
    new JsonSubTypes.Type(value = classOf[Addr.Neg], name = "neg"),
    new JsonSubTypes.Type(value = classOf[Addr.Pending], name = "pending")
  ))
  sealed trait Addr
  object Addr {
    case class Pending() extends Addr
    case class Neg() extends Addr
    case class Failed(cause: String) extends Addr
    case class Bound(addrs: Set[Address], meta: Map[String, Any]) extends Addr

    def mk(fa: FAddr): Addr = fa match {
      case FAddr.Bound(addrs, meta) => Bound(addrs.flatMap(Address.mk), meta)
      case FAddr.Failed(e) => Failed(e.getMessage)
      case FAddr.Neg => Neg()
      case FAddr.Pending => Pending()
    }

    def toFinagle(addr: Addr): FAddr = addr match {
      case Pending() => FAddr.Pending
      case Neg() => FAddr.Neg
      case Failed(cause) => FAddr.Failed(cause)
      case Bound(addrs, meta) => FAddr.Bound(addrs.map(Address.toFinagle), meta)
    }
  }

  case class Bound(addr: Addr, id: Path, path: Path)
  object Bound {
    def mk(path: Path, name: Name.Bound): Future[Bound] = {
      val id = name.id match {
        case id: Path => id
        case _ => path
      }
      name.addr.changes.filter(_ != FAddr.Pending).toFuture.map { addr =>
        Bound(Addr.mk(addr), id, name.path)
      }
    }
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
  @JsonSubTypes(Array(
    new JsonSubTypes.Type(value = classOf[JsonDelegateTree.Empty], name = "empty"),
    new JsonSubTypes.Type(value = classOf[JsonDelegateTree.Fail], name = "fail"),
    new JsonSubTypes.Type(value = classOf[JsonDelegateTree.Neg], name = "neg"),
    new JsonSubTypes.Type(value = classOf[JsonDelegateTree.Exception], name = "exception"),
    new JsonSubTypes.Type(value = classOf[JsonDelegateTree.Delegate], name = "delegate"),
    new JsonSubTypes.Type(value = classOf[JsonDelegateTree.Leaf], name = "leaf"),
    new JsonSubTypes.Type(value = classOf[JsonDelegateTree.Alt], name = "alt"),
    new JsonSubTypes.Type(value = classOf[JsonDelegateTree.Union], name = "union"),
    new JsonSubTypes.Type(value = classOf[JsonDelegateTree.Transformation], name = "transformation")
  ))
  sealed trait JsonDelegateTree
  object JsonDelegateTree {
    case class Empty(path: Path, dentry: Option[Dentry]) extends JsonDelegateTree
    case class Fail(path: String, dentry: Option[Dentry]) extends JsonDelegateTree
    case class Neg(path: String, dentry: Option[Dentry]) extends JsonDelegateTree
    case class Exception(path: Path, dentry: Option[Dentry], message: String) extends JsonDelegateTree
    case class Delegate(path: Path, dentry: Option[Dentry], delegate: JsonDelegateTree) extends JsonDelegateTree
    case class Leaf(path: Path, dentry: Option[Dentry], bound: Bound) extends JsonDelegateTree
    case class Alt(path: Path, dentry: Option[Dentry], alt: Seq[JsonDelegateTree]) extends JsonDelegateTree
    case class Union(path: Path, dentry: Option[Dentry], union: Seq[Weighted]) extends JsonDelegateTree
    case class Weighted(weight: Double, tree: JsonDelegateTree)
    case class Transformation(path: Path, name: String, bound: Bound, tree: JsonDelegateTree) extends JsonDelegateTree

    private[this] val fail = Path.read("/$/fail")

    def mk(d: DelegateTree[Name.Bound]): Future[JsonDelegateTree] = d match {
      case DelegateTree.Exception(p, d, e) =>
        Future.value(JsonDelegateTree.Exception(p, mkDentry(d), e.getMessage))
      case DelegateTree.Empty(p, d) =>
        Future.value(JsonDelegateTree.Empty(p, mkDentry(d)))
      case DelegateTree.Fail(p, d) =>
        val path = if (p == null) null
        else if (p.startsWith(fail)) p.show
        else "!"
        Future.value(JsonDelegateTree.Fail(path, mkDentry(d)))
      case DelegateTree.Neg(p, d) =>
        val path = if (p == null) null
        else if (p.isEmpty) "~"
        else p.show
        Future.value(JsonDelegateTree.Neg(path, mkDentry(d)))
      case DelegateTree.Delegate(p, d, t) =>
        mk(t).map(JsonDelegateTree.Delegate(p, mkDentry(d), _))
      case DelegateTree.Alt(p, d, ts@_*) =>
        Future.collect(ts.map(mk)).map(JsonDelegateTree.Alt(p, mkDentry(d), _))
      case DelegateTree.Union(p, d, ts@_*) =>
        val weights = ts.map { case DelegateTree.Weighted(w, t) => mk(t).map(Weighted(w, _)) }
        Future.collect(weights).map(JsonDelegateTree.Union(p, mkDentry(d), _))
      case DelegateTree.Leaf(p, d, b) =>
        Bound.mk(p, b).map(JsonDelegateTree.Leaf(p, mkDentry(d), _))
      case DelegateTree.Transformation(p, n, b, t) =>
        mk(t).join(Bound.mk(p, b)).map {
          case (tree, bound) =>
            JsonDelegateTree.Transformation(p, n, bound, tree)
        }
    }

    def parseBound(bound: Bound): Name.Bound = Name.Bound(Var(Addr.toFinagle(bound.addr)), bound.id, bound.path)

    def toDelegateTree(jdt: JsonDelegateTree): DelegateTree[Name.Bound] = jdt match {
      case Empty(p, d) =>
        DelegateTree.Empty(p, d.getOrElse(Dentry.nop))
      case Fail(p, d) =>
        DelegateTree.Fail(Path.read(p), d.getOrElse(Dentry.nop))
      case Neg(p, d) =>
        DelegateTree.Neg(Path.read(p), d.getOrElse(Dentry.nop))
      case Exception(p, d, msg) =>
        DelegateTree.Exception(p, d.getOrElse(Dentry.nop), new java.lang.Exception(msg))
      case Delegate(p, d, delegate) =>
        DelegateTree.Delegate(p, d.getOrElse(Dentry.nop), toDelegateTree(delegate))
      case Leaf(p, d, bound) =>
        DelegateTree.Leaf(p, d.getOrElse(Dentry.nop), parseBound(bound))
      case Alt(p, d, alts) =>
        DelegateTree.Alt(p, d.getOrElse(Dentry.nop), alts.map(toDelegateTree): _*)
      case Union(p, d, weighteds) =>
        val delegateTreeWeighteds = weighteds.map {
          case Weighted(w, tree) =>
            DelegateTree.Weighted(w, toDelegateTree(tree))
        }
        DelegateTree.Union(p, d.getOrElse(Dentry.nop), delegateTreeWeighteds: _*)
      case Transformation(p, name, bound, tree) =>
        DelegateTree.Transformation(p, name, parseBound(bound), toDelegateTree(tree))
    }

    def toNameTree(d: JsonDelegateTree): NameTree[Name.Bound] = d match {
      case Empty(_, _) => NameTree.Empty
      case Fail(_, _) => NameTree.Fail
      case Neg(_, _) => NameTree.Neg
      case Exception(_, _, msg) => throw new IllegalStateException(msg)
      case Delegate(_, _, delegate) => toNameTree(delegate)
      case Leaf(_, _, bound) =>
        NameTree.Leaf(Name.Bound(Var(Addr.toFinagle(bound.addr)), bound.id, bound.path))
      case Alt(_, _, alts) => NameTree.Alt(alts.map(toNameTree): _*)
      case Union(_, _, weighteds) =>
        val nameTreeWeighteds = weighteds.map {
          case Weighted(w, tree) =>
            NameTree.Weighted(w, toNameTree(tree))
        }
        NameTree.Union(nameTreeWeighteds: _*)
      case Transformation(_, _, _, tree) => toNameTree(tree)
    }

    def mkDentry(d: Dentry): Option[Dentry] = Some(d).filterNot(Dentry.equiv.equiv(Dentry.nop, _))
  }

  object Codec {
    private[this] def mkModule() = {
      val module = new SimpleModule

      module.addSerializer(classOf[Path], new JsonSerializer[Path] {
        override def serialize(path: Path, json: JsonGenerator, p: SerializerProvider) {
          json.writeString(path.show)
        }
      })
      module.addDeserializer(classOf[Path], new JsonDeserializer[Path] {
        override def deserialize(json: JsonParser, ctx: DeserializationContext) =
          Path.read(json.getValueAsString)
      })

      module.addSerializer(classOf[Dentry.Prefix], new JsonSerializer[Dentry.Prefix] {
        override def serialize(pfx: Dentry.Prefix, json: JsonGenerator, p: SerializerProvider) {
          json.writeString(pfx.show)
        }
      })
      module.addDeserializer(classOf[Dentry.Prefix], new JsonDeserializer[Dentry.Prefix] {
        override def deserialize(json: JsonParser, ctx: DeserializationContext) =
          Dentry.Prefix.read(json.getValueAsString)
      })

      module.addSerializer(classOf[NameTree[Path]], new JsonSerializer[NameTree[Path]] {
        override def serialize(
          nameTree: NameTree[Path],
          json: JsonGenerator,
          p: SerializerProvider
        ) {
          json.writeString(nameTree.show)
        }
      })

      module.addDeserializer(classOf[NameTree[Path]], new JsonDeserializer[NameTree[Path]] {
        override def deserialize(json: JsonParser, ctx: DeserializationContext) =
          NameTree.read(json.getValueAsString)
      })

      module
    }

    val mapper = new ObjectMapper with ScalaObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    mapper.setSerializationInclusion(JsonInclude.Include.NON_ABSENT)
    mapper.registerModule(mkModule())

    def writeStr[T](t: T): String = mapper.writeValueAsString(t)
    def writeBuf[T](t: T): Buf = Buf.ByteArray.Owned(mapper.writeValueAsBytes(t))
    def readBuf[T: Manifest](buf: Buf): Try[T] = {
      val Buf.ByteBuffer.Owned(bb) = Buf.ByteBuffer.coerce(buf)
      Try { mapper.readValue[T](bb.array) }
    }
  }

  private implicit val timer = DefaultTimer

  def getDelegateRsp(dtab: String, path: String, delegator: Delegator): Future[Response] = {
    val dtabTry = if (dtab == null) Return(Dtab.empty) else Try(Dtab.read(dtab))
    val pathTry = Try(Path.read(path))
    (dtabTry, pathTry) match {
      case (Return(d), Return(p)) =>
        delegator.delegate(d, p)
          .toFuture
          .flatMap(JsonDelegateTree.mk).map { tree =>
            val rsp = Response()
            rsp.content = Codec.writeBuf(tree)
            rsp.contentType = MediaType.Json
            rsp
          }.within(2.seconds).rescue {
            case e: TimeoutException =>
              err(Status.ServiceUnavailable, "Request timed out.")
          }
      case (Throw(e), _) =>
        err(Status.BadRequest, s"Invalid dtab: ${e.getMessage}")
      case (_, Throw(e)) =>
        err(Status.BadRequest, s"Invalid path: ${e.getMessage}")
    }
  }

  case class DelegationRequest(
    namespace: Option[String],
    dtab: Option[String],
    path: Option[String]
  )

  sealed trait DelegationRequestCodec {
    def contentTypes: Set[String]
    def read(buf: Buf): Try[DelegationRequest]
  }

  object DelegationRequestCodec {

    object JsonCodec extends DelegationRequestCodec {
      val contentTypes = Set(MediaType.Json)
      def read(buf: Buf): Try[DelegationRequest] = Codec.readBuf[DelegationRequest](buf)
    }

    def byContentType(ct: String): Option[DelegationRequestCodec] =
      if (JsonCodec.contentTypes(ct)) Some(DelegationRequestCodec.JsonCodec) else None
  }

}

class DelegateApiHandler(
  interpreters: String => NameInterpreter,
  namers: Seq[(Path, Namer)] = Nil
) extends Service[Request, Response] {

  import DelegateApiHandler._

  def apply(req: Request): Future[Response] = req.method match {
    case Method.Post =>
      req.contentType.flatMap(DelegationRequestCodec.byContentType) match {
        case Some(codec) =>
          codec.read(req.content) match {
            case Return(DelegationRequest(ns, Some(dtab), Some(path))) => getResponse(ns, dtab, path)
            case _ => err(Status.BadRequest, s"Malformed delegation request: ${req.getContentString}")
          }
        case _ => err(Status.UnsupportedMediaType)
      }
    case Method.Get =>
      getResponse(req.params.get("namespace"), req.getParam("dtab"), req.getParam("path"))
    case _ => err(Status.MethodNotAllowed)
  }

  private def getResponse(ns: Option[String], dtab: String, path: String) = ns match {
    case Some(namespace) =>
      interpreters(namespace) match {
        case delegator: Delegator =>
          getDelegateRsp(dtab, path, delegator)
        case _ =>
          err(Status.NotImplemented, s"Name Interpreter for $namespace cannot show delegations")
      }
    case None =>
      getDelegateRsp(dtab, path, ConfiguredNamersInterpreter(namers))
  }
}
