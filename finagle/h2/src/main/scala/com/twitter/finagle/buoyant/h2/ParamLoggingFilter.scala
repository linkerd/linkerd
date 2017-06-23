package com.twitter.finagle.buoyant.h2

import com.twitter.finagle._
import com.twitter.finagle.buoyant.h2.param.ClientPriorKnowledge
import com.twitter.finagle.buoyant.h2.param.FlowControl.{AutoRefillConnectionWindow, WindowUpdateRatio}
import com.twitter.finagle.buoyant.h2.param.Settings.{HeaderTableSize, InitialStreamWindowSize, MaxConcurrentStreams, MaxFrameSize, MaxHeaderListSize}
import com.twitter.finagle.ssl.server.SslServerEngineFactory
import com.twitter.finagle.transport.Transport

object ParamLoggingFilter {
  val role = Stack.Role("ParamLoggingFilter")
  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.ModuleParams[ServiceFactory[Request, Response]] {
      val role = ParamLoggingFilter.role
      val description = "Prints all stack params"
      val parameters = Seq(
        implicitly[Stack.Param[SslServerEngineFactory.Param]],
        implicitly[Stack.Param[Transport.ServerSsl]],
        implicitly[Stack.Param[ClientPriorKnowledge]],
        implicitly[Stack.Param[AutoRefillConnectionWindow]],
        implicitly[Stack.Param[WindowUpdateRatio]],
        implicitly[Stack.Param[HeaderTableSize]],
        implicitly[Stack.Param[InitialStreamWindowSize]],
        implicitly[Stack.Param[MaxConcurrentStreams]],
        implicitly[Stack.Param[MaxFrameSize]],
        implicitly[Stack.Param[MaxHeaderListSize]]
      )
      def make(params: Stack.Params, next: ServiceFactory[Request, Response]): ServiceFactory[Request, Response] = {
        next
      }
    }
}

class ParamLoggingFilter extends SimpleFilter[Request, Response] {
  def apply(req: Request, service: Service[Request, Response]) = service(req)
}
