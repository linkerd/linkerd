package io.buoyant.linkerd
package protocol

import com.twitter.finagle.Path
import com.twitter.finagle.Thrift.param
import com.twitter.finagle.thrift.Protocols
import io.buoyant.router.{Thrift, RoutingFactory}
import org.apache.thrift.protocol.TCompactProtocol

class ThriftInitializer extends ProtocolInitializer {
  val name = "thrift"

  protected type RouterReq = com.twitter.finagle.thrift.ThriftClientRequest
  protected type RouterRsp = Array[Byte]
  protected type ServerReq = Array[Byte]
  protected type ServerRsp = Array[Byte]

  protected val defaultRouter = Thrift.router
    .configured(RoutingFactory.DstPrefix(Path.Utf8(name)))

  protected val adapter = Thrift.Router.IngestingFilter
  protected val defaultServer = Thrift.server
    .configured(Server.Port(4114))

  val Framed = Parsing.Param.Boolean("thriftFramed") { framed =>
    param.Framed(framed)
  }

  val MethodInDst = Parsing.Param.Boolean("thriftMethodInDst") { methodInDst =>
    Thrift.param.MethodInDst(methodInDst)
  }

  val Protocol = Parsing.Param.Text("thriftProtocol") { protocol =>
    val factory = protocol match {
      case "binary" => Protocols.binaryFactory()
      case "compact" => new TCompactProtocol.Factory()
      case _ => throw new IllegalArgumentException(s"unsupported thrift protocol $protocol")
    }
    param.ProtocolFactory(factory)
  }

  override val routerParamsParser = MethodInDst
  override val serverParamsParser = Framed.andThen(Protocol)
  override val clientParamsParser = Framed.andThen(Protocol)
}
