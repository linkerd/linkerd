package io.buoyant.linkerd
package protocol

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonProperty, JsonSubTypes, JsonTypeInfo}
import com.twitter.finagle.Stack.Params
import com.twitter.finagle.Thrift.param
import com.twitter.finagle.Thrift.param.{AttemptTTwitterUpgrade, ProtocolFactory}
import com.twitter.finagle.buoyant.PathMatcher
import com.twitter.finagle.buoyant.linkerd.{ThriftClientPrep, ThriftServerPrep, ThriftTraceInitializer}
import io.buoyant.config.types.ThriftProtocol
import io.buoyant.router.Thrift

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
      .replace(ThriftTraceInitializer.role, ThriftTraceInitializer.serverModule[Array[Byte], Array[Byte]])
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
  var service: Option[Svc] = None

  @JsonProperty("client")
  var _client: Option[ThriftClient] = None

  @JsonIgnore
  def client = _client.orElse(Some(new ThriftDefaultClient))

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

@JsonTypeInfo(
  use = JsonTypeInfo.Id.NAME,
  include = JsonTypeInfo.As.EXISTING_PROPERTY,
  property = "kind",
  visible = true,
  defaultImpl = classOf[ThriftDefaultClient]
)
@JsonSubTypes(Array(
  new JsonSubTypes.Type(value = classOf[ThriftDefaultClient], name = "io.l5d.global"),
  new JsonSubTypes.Type(value = classOf[ThriftStaticClient], name = "io.l5d.static")
))
abstract class ThriftClient extends Client

class ThriftDefaultClient extends ThriftClient with DefaultClient with ThriftClientConfig

class ThriftStaticClient(val configs: Seq[ThriftPrefixConfig]) extends ThriftClient with StaticClient

class ThriftPrefixConfig(prefix: PathMatcher) extends PrefixConfig(prefix) with ThriftClientConfig

trait ThriftClientConfig extends ClientConfig {

  var thriftFramed: Option[Boolean] = None
  var thriftProtocol: Option[ThriftProtocol] = None
  var attemptTTwitterUpgrade: Option[Boolean] = None

  @JsonIgnore
  override def params(vars: Map[String, String]) = super.params(vars)
    .maybeWith(thriftFramed.map(param.Framed(_)))
    .maybeWith(thriftProtocol.map(proto => param.ProtocolFactory(proto.factory))) +
    AttemptTTwitterUpgrade(attemptTTwitterUpgrade.getOrElse(false))
}
