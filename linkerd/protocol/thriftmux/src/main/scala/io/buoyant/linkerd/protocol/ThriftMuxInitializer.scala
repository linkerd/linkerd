package io.buoyant.linkerd
package protocol

import com.twitter.finagle.Stack
import com.twitter.finagle.buoyant.linkerd.{ThriftClientPrep, ThriftServerPrep, ThriftTraceInitializer}
import io.buoyant.router.{Thrift, ThriftMux}

class ThriftMuxInitializer extends ProtocolInitializer {
  val name = "thriftmux"

  override val experimentalRequired = true

  protected type RouterReq = com.twitter.finagle.thrift.ThriftClientRequest
  protected type RouterRsp = Array[Byte]
  protected type ServerReq = Array[Byte]
  protected type ServerRsp = Array[Byte]

  protected val defaultRouter = {
    val stack = ThriftMux.router.clientStack
      .replace(ThriftClientPrep.role, ThriftClientPrep.module)
    ThriftMux.router.withClientStack(stack)
  }

  protected val adapter = Thrift.Router.IngestingFilter

  protected val defaultServer = {
    val serverMuxer = ThriftMux.server().muxer
    val stack = serverMuxer.stack
      .insertBefore(Stack.Role("appExceptionHandling"), ThriftTraceInitializer.serverModule)
      .replace(ThriftServerPrep.role, ThriftServerPrep.module)
    ThriftMux.server(serverMuxer.withStack(stack))
  }

  override def defaultServerPort: Int = 4144

  val configClass = classOf[ThriftConfig]
}

object ThriftMuxInitializer extends ThriftMuxInitializer