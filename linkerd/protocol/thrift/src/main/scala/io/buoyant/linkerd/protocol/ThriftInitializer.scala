package io.buoyant.linkerd
package protocol

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonProperty}
import com.twitter.finagle.Path
import com.twitter.finagle.Stack.Params
import com.twitter.finagle.Thrift.param
import com.twitter.finagle.Thrift.param.{AttemptTTwitterUpgrade, ProtocolFactory}
import com.twitter.finagle.buoyant.linkerd.{ThriftClientPrep, ThriftServerPrep, ThriftTraceInitializer}
import io.buoyant.config.Parser
import io.buoyant.config.types.ThriftProtocol
import io.buoyant.router.{RoutingFactory, Thrift}
import org.apache.thrift.protocol.TProtocolFactory

class ThriftInitializer extends ProtocolInitializer {
  val name = "thrift"

  protected type RouterReq = com.twitter.finagle.thrift.ThriftClientRequest
  protected type RouterRsp = Array[Byte]
  protected type ServerReq = Array[Byte]
  protected type ServerRsp = Array[Byte]

  protected val defaultRouter = {
    val clientStack = Thrift.router.clientStack
      .replace(ThriftClientPrep.role, ThriftClientPrep.module)
    Thrift.router.withClientStack(clientStack)
  }

  protected val adapter = Thrift.Router.IngestingFilter
  protected val defaultServer = {
    val stack = Thrift.server.stack
      .replace(ThriftTraceInitializer.role, ThriftTraceInitializer.serverModule)
      .replace(ThriftServerPrep.role, ThriftServerPrep.module)
    Thrift.server.withStack(stack)
  }

  override def defaultServerPort: Int = 4114

  val configClass = classOf[ThriftConfig]
}

object ThriftInitializer extends ThriftInitializer

case class ThriftConfig(
  thriftMethodInDst: Option[Boolean]
) extends RouterConfig {

  var servers: Seq[ThriftServerConfig] = Nil
  @JsonProperty("client")
  var _client: Option[ThriftClientConfig] = None

  def client: Option[ThriftClientConfig] = _client.orElse(Some(ThriftClientConfig()))

  @JsonIgnore
  override def protocol = ThriftInitializer

  override def routerParams = super.routerParams
    .maybeWith(thriftMethodInDst.map(Thrift.param.MethodInDst(_)))
}

case class ThriftServerConfig(
  thriftFramed: Option[Boolean],
  thriftProtocol: Option[ThriftProtocol]
) extends ServerConfig {
  @JsonIgnore
  override protected def serverParams: Params = super.serverParams
    .maybeWith(thriftFramed.map(param.Framed(_)))
    .maybeWith(thriftProtocol.map(proto => param.ProtocolFactory(proto.factory)))
}

case class ThriftClientConfig(
  thriftFramed: Option[Boolean] = None,
  thriftProtocol: Option[ThriftProtocol] = None,
  attemptTTwitterUpgrade: Option[Boolean] = None
) extends ClientConfig {
  @JsonIgnore
  override def clientParams: Params = super.clientParams
    .maybeWith(thriftFramed.map(param.Framed(_)))
    .maybeWith(thriftProtocol.map(proto => param.ProtocolFactory(proto.factory))) +
    AttemptTTwitterUpgrade(attemptTTwitterUpgrade.getOrElse(false))
}
