package io.buoyant.router.thrift

import com.twitter.finagle.Thrift.{param => tparam}
import com.twitter.finagle.client.StackClient
import com.twitter.finagle.thrift.ThriftClientRequest
import com.twitter.finagle.tracing.Trace
import com.twitter.finagle._
import org.apache.thrift.protocol.TProtocolFactory
import org.apache.thrift.transport.TMemoryInputTransport

object TracingFilter {
  val role = StackClient.Role.protoTracing
  val module: Stackable[ServiceFactory[ThriftClientRequest, Array[Byte]]] =
    new Stack.Module2[param.Tracer, tparam.ProtocolFactory, ServiceFactory[ThriftClientRequest, Array[Byte]]] {
      val role = TracingFilter.role
      val description = "Traces Thrift-specific request metadata"
      def make(_tracer: param.Tracer, _pf: tparam.ProtocolFactory, next: ServiceFactory[ThriftClientRequest, Array[Byte]]) = {
        val param.Tracer(tracer) = _tracer
        if (tracer.isNull) next
        else {
          val tparam.ProtocolFactory(pf) = _pf
          new TracingFilter(pf).andThen(next)
        }
      }
    }
}

class TracingFilter(protocol: TProtocolFactory) extends SimpleFilter[ThriftClientRequest, Array[Byte]] {

  def apply(req: ThriftClientRequest, service: Service[ThriftClientRequest, Array[Byte]]) = {
    recordRequest(req)
    service(req)
  }

  private[this] def recordRequest(req: ThriftClientRequest): Unit =
    if (Trace.isActivelyTracing) {
      val messageName = protocol.getProtocol(
        new TMemoryInputTransport(req.message)
      ).readMessageBegin().name
      Trace.recordRpc(messageName)
    }
}
