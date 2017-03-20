package io.buoyant.linkerd
package protocol

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonProperty}
import com.twitter.finagle.Stack
import com.twitter.finagle.buoyant.linkerd.{ThriftClientPrep, ThriftMuxServerPrep, ThriftTraceInitializer}
import com.twitter.finagle.mux
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
  thriftMethodInDst: Option[Boolean]
) extends RouterConfig {

  var servers: Seq[ThriftServerConfig] = Nil
  @JsonProperty("client")
  var _client: Option[ThriftClientConfig] = None

  def client: Option[ThriftClientConfig] = _client.orElse(Some(ThriftClientConfig()))

  @JsonIgnore
  override def protocol = ThriftMuxInitializer

  override def routerParams = super.routerParams
    .maybeWith(thriftMethodInDst.map(Thrift.param.MethodInDst(_)))
}