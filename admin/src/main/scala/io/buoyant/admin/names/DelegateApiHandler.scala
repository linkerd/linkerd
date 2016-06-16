package io.buoyant
package admin.names

import com.fasterxml.jackson.annotation._
import com.fasterxml.jackson.core.{io => _, _}
import com.fasterxml.jackson.databind._
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.finagle.http._
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.{Address => FAddress, Addr => FAddr, Path, Status => _, _}
import com.twitter.io.Buf
import com.twitter.util._
import io.buoyant.namer._

object DelegateApiHandler {

  private object PathStr {
    def unapply(p: String) = Try(Path.read(p)).toOption
  }

  private object DtabStr {
    def unapply(d: String) = if (d == null)
      Some(Dtab.empty)
    else
      Try(Dtab.read(d)).toOption
  }

  private def err(status: Status) = Future.value(Response(status))

  private def err(status: Status, content: String) = {
    val resp = Response(status)
    resp.contentString = content
    Future.value(resp)
  }

  case class Address(ip: String, port: Int)
  object Address {
    def mk(addr: FAddress): Option[Address] = addr match {
      case FAddress.Inet(isa, _) => Some(Address(isa.getAddress.getHostAddress, isa.getPort))
      case _ => None
    }
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
    new JsonSubTypes.Type(value = classOf[JsonDelegateTree.Union], name = "union")
  ))
  sealed trait JsonDelegateTree
  object JsonDelegateTree {
    case class Empty(path: Path, dentry: Option[Dentry]) extends JsonDelegateTree
    case class Fail(path: Path, dentry: Option[Dentry]) extends JsonDelegateTree
    case class Neg(path: Path, dentry: Option[Dentry]) extends JsonDelegateTree
    case class Exception(path: Path, dentry: Option[Dentry], message: String) extends JsonDelegateTree
    case class Delegate(path: Path, dentry: Option[Dentry], delegate: JsonDelegateTree) extends JsonDelegateTree
    case class Leaf(path: Path, dentry: Option[Dentry], bound: Bound) extends JsonDelegateTree
    case class Alt(path: Path, dentry: Option[Dentry], alt: Seq[JsonDelegateTree]) extends JsonDelegateTree
    case class Union(path: Path, dentry: Option[Dentry], union: Seq[Weighted]) extends JsonDelegateTree
    case class Weighted(weight: Double, tree: JsonDelegateTree)

    def mk(d: DelegateTree[Name.Bound]): Future[JsonDelegateTree] = d match {
      case DelegateTree.Exception(p, d, e) =>
        Future.value(JsonDelegateTree.Exception(p, mkDentry(d), e.getMessage))
      case DelegateTree.Empty(p, d) =>
        Future.value(JsonDelegateTree.Empty(p, mkDentry(d)))
      case DelegateTree.Fail(p, d) =>
        Future.value(JsonDelegateTree.Fail(p, mkDentry(d)))
      case DelegateTree.Neg(p, d) =>
        Future.value(JsonDelegateTree.Neg(p, mkDentry(d)))
      case DelegateTree.Delegate(p, d, t) =>
        mk(t).map(JsonDelegateTree.Delegate(p, mkDentry(d), _))
      case DelegateTree.Alt(p, d, ts@_*) =>
        Future.collect(ts.map(mk)).map(JsonDelegateTree.Alt(p, mkDentry(d), _))
      case DelegateTree.Union(p, d, ts@_*) =>
        val weights = ts.map { case DelegateTree.Weighted(w, t) => mk(t).map(Weighted(w, _)) }
        Future.collect(weights).map(JsonDelegateTree.Union(p, mkDentry(d), _))
      case DelegateTree.Leaf(p, d, b) =>
        Bound.mk(p, b).map(JsonDelegateTree.Leaf(p, mkDentry(d), _))
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

    private[this] val mapper = new ObjectMapper with ScalaObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    mapper.registerModule(mkModule())

    def writeStr[T](t: T): String = mapper.writeValueAsString(t)
    def writeBuf[T](t: T): Buf = Buf.ByteArray.Owned(mapper.writeValueAsBytes(t))
  }

  def getDelegateRsp(dtab: String, path: String, delegator: Delegator): Future[Response] =
    (dtab, path) match {
      case (DtabStr(d), PathStr(p)) =>
        delegator.delegate(d, p).values.toFuture()
          .flatMap(Future.const)
          .flatMap(JsonDelegateTree.mk).map { tree =>
            val rsp = Response()
            rsp.content = Codec.writeBuf(tree)
            rsp.contentType = MediaType.Json
            rsp
          }
      case _ => err(Status.BadRequest)
    }
}

class DelegateApiHandler(
  interpreters: String => NameInterpreter
) extends Service[Request, Response] {

  import DelegateApiHandler._

  def apply(req: Request): Future[Response] = req.method match {
    case Method.Get =>
      req.params.get("namespace") match {
        case Some(ns) =>
          interpreters(ns) match {
            case delegator: Delegator =>
              getDelegateRsp(req.getParam("dtab"), req.getParam("path"), delegator)
            case _ =>
              err(Status.NotImplemented, s"Name Interpreter for $ns cannot show delegations")
          }
        case None =>
          getDelegateRsp(req.getParam("dtab"), req.getParam("path"), ConfiguredNamersInterpreter(Nil))
      }
    case _ => err(Status.MethodNotAllowed)
  }
}
