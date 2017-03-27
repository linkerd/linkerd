package com.twitter.finagle.buoyant.linkerd

import com.twitter.finagle.client.StackClient
import com.twitter.finagle.mux.{Request, Response}
import com.twitter.finagle._
import com.twitter.io.Buf
import com.twitter.util.Future

object ThriftMuxServerPrep {
  val role: Stack.Role = StackClient.Role.prepConn

  val module = new Stack.ModuleParams[ServiceFactory[Request, Response]] {
    override def parameters: Seq[Stack.Param[_]] = Nil

    override val role: Stack.Role = ThriftServerPrep.role
    override val description = "Prepare ThriftMux connection"

    def make(
      params: Stack.Params,
      next: ServiceFactory[Request, Response]
    ): ServiceFactory[Request, Response] = {
      val arrayBytesServiceFactory = ArrayBytesToMuxFilter.andThen(next)
      MuxToArrayBytesFilter.andThen(ThriftServerPrep.module.make(params, arrayBytesServiceFactory))
    }
  }

  object MuxToArrayBytesFilter extends Filter[mux.Request, mux.Response, Array[Byte], Array[Byte]] {
    def apply(request: mux.Request, service: Service[Array[Byte], Array[Byte]]): Future[mux.Response] = {
      val reqBytes = Buf.ByteArray.Owned.extract(request.body)
      service(reqBytes).map { repBytes =>
        mux.Response(Buf.ByteArray.Owned(repBytes))
      }
    }
  }

  object ArrayBytesToMuxFilter extends Filter[Array[Byte], Array[Byte], mux.Request, mux.Response] {
    def apply(request: Array[Byte], service: Service[mux.Request, mux.Response]): Future[Array[Byte]] = {
      val req = mux.Request(Path.empty, Buf.ByteArray.Owned(request))
      service(req).map { rep =>
        Buf.ByteArray.Owned.extract(rep.body)
      }
    }
  }
}
