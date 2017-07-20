package io.buoyant.linkerd
package protocol

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonProperty}
import com.twitter.finagle.Stack
import com.twitter.finagle.Thrift.param
import com.twitter.finagle.buoyant.linkerd.{ThriftClientPrep, ThriftMuxServerPrep, ThriftTraceInitializer}
import com.twitter.finagle.mux
import com.twitter.finagle.buoyant.ParamsMaybeWith
import io.buoyant.config.types.ThriftProtocol
import io.buoyant.router.{Thrift, ThriftMux}

class ThriftMuxInitializer extends ProtocolInitializer {
  val name = "thriftmux"

  override val experimentalRequired = true

  protected type RouterReq = com.twitter.finagle.thrift.ThriftClientRequest
  protected type RouterRsp = Array[Byte]
  protected type ServerReq = mux.Request
  protected type ServerRsp = mux.Response

  protected val defaultRouter = {
    val stack = ThriftMux.router.clientStack
      .replace(ThriftClientPrep.role, ThriftClientPrep.module)
    ThriftMux.router.withClientStack(stack)
  }

  override protected def configureServer(router: Router, server: Server): Server =
    super.configureServer(router, server)
      .configured(router.params[param.ProtocolFactory])

  protected val adapter = ThriftMux.Router.IngestingFilter
  protected val defaultServer = {
    val stack = ThriftMux.server.stack
      .insertBefore(Stack.Role("appExceptionHandling"), ThriftTraceInitializer.serverModule[mux.Request, mux.Response])
      .replace(ThriftMuxServerPrep.role, ThriftMuxServerPrep.module)
    ThriftMux.server.withStack(stack)
  }

  override def defaultServerPort: Int = 4144

  val configClass = classOf[ThriftMuxConfig]
}

object ThriftMuxInitializer extends ThriftMuxInitializer

case class ThriftMuxConfig(
  thriftMethodInDst: Option[Boolean],
  thriftProtocol: Option[ThriftProtocol]
) extends RouterConfig {

  var servers: Seq[ThriftServerConfig] = Nil
  var service: Option[Svc] = None
  @JsonProperty("client")
  var _client: Option[ThriftClient] = None

  def client: Option[ThriftClient] = _client.orElse(Some(new ThriftDefaultClient))

  @JsonIgnore
  override def protocol = ThriftMuxInitializer

  override def routerParams = super.routerParams
    .maybeWith(thriftMethodInDst.map(Thrift.param.MethodInDst(_)))
    .maybeWith(thriftProtocol.map(proto => param.ProtocolFactory(proto.factory)))
}
