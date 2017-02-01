package io.buoyant.linkerd
package protocol

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Path, Stack}
import com.twitter.finagle.buoyant.linkerd.ThriftMuxTraceInitializer
import io.buoyant.router.{RoutingFactory, Thrift, ThriftMux}

class ThriftMuxInitializer extends ProtocolInitializer {
  val name = "thriftmux"

  protected type RouterReq = com.twitter.finagle.thrift.ThriftClientRequest
  protected type RouterRsp = Array[Byte]
  protected type ServerReq = Array[Byte]
  protected type ServerRsp = Array[Byte]

  protected val defaultRouter = ThriftMux.router
    .configured(RoutingFactory.DstPrefix(Path.Utf8(name)))

  protected val adapter = Thrift.Router.IngestingFilter

  protected val defaultServer = {
    val serverMuxer = ThriftMux.server().muxer
    val stack = serverMuxer.stack
      .insertBefore(Stack.Role("appExceptionHandling"), ThriftMuxTraceInitializer.serverModule)
    ThriftMux.server(serverMuxer.withStack(stack))
  }

  override def defaultServerPort: Int = 4144

  val configClass = classOf[ThriftMuxConfig]
}

object ThriftMuxInitializer extends ThriftMuxInitializer

class ThriftMuxConfig(thriftMethodInDst: Option[Boolean]) extends RouterConfig {

  var servers: Seq[ServerConfig] = Nil
  var client: Option[ClientConfig] = None

  @JsonIgnore
  override def protocol = ThriftMuxInitializer

  override def routerParams = super.routerParams
    .maybeWith(thriftMethodInDst.map(Thrift.param.MethodInDst(_)))
}