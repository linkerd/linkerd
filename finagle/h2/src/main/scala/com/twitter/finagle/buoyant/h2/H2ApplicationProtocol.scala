package com.twitter.finagle.buoyant.h2

import com.twitter.finagle.Stack.Module
import com.twitter.finagle.ssl.ApplicationProtocols
import com.twitter.finagle.transport.Transport
import com.twitter.finagle.{ServiceFactory, Stack}
import io.netty.handler.ssl.ApplicationProtocolNames

object H2ApplicationProtocol {

  val role = Stack.Role("ApplicationProtocol")

  def module = new Module[ServiceFactory[Request, Response]] {

    override def role: Stack.Role = H2ApplicationProtocol.role
    override def description = "Advertise h2 support"
    override def parameters: Seq[Stack.Param[_]] = Seq(
      implicitly(Stack.Param(Transport.ClientSsl))
    )

    override def make(
      params: Stack.Params,
      next: Stack[ServiceFactory[Request, Response]]
    ): Stack[ServiceFactory[Request, Response]] = {
      val sslParam = params[Transport.ClientSsl].e.map { config =>
        val protocols = config.applicationProtocols match {
          case ApplicationProtocols.Supported(protos) => protos :+ ApplicationProtocolNames.HTTP_2
          case ApplicationProtocols.Unspecified => Seq(ApplicationProtocolNames.HTTP_2)
        }
        config.copy(applicationProtocols = ApplicationProtocols.Supported(protocols))
      }
      Stack.Leaf(role, next.make(params + Transport.ClientSsl(sslParam)))
    }
  }
}
