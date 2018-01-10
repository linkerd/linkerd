package com.twitter.finagle.client.buoyant

import com.twitter.finagle.{Addr, Address, Path, Service}
import com.twitter.finagle.client.ClientRegistry
import com.twitter.finagle.http.{MediaType, Request, Response}
import com.twitter.finagle.loadbalancer.LoadBalancerFactory
import com.twitter.util.{Future, Try}
import io.buoyant.config.Parser

class ClientStateHandler extends Service[Request, Response] {

  private case class ClientState(state: String, addresses: Seq[String] = Nil)

  private[this] val mapper = Parser.jsonObjectMapper(Nil)

  override def apply(request: Request): Future[Response] = {
    val entries = ClientRegistry.registrants
      .filter { entry =>
        // only display clients with a Path name
        Try(Path.read(entry.addr)).isReturn
      }.map { entry =>
        val LoadBalancerFactory.Dest(va) = entry.params[LoadBalancerFactory.Dest]
        val clientState = va.sample() match {
          case Addr.Bound(addresses, _) =>
            ClientState("bound", addresses.map {
              case Address.Inet(isa, _) => s"${isa.getHostString}:${isa.getPort}"
              case a => a.toString
            }.toSeq)
          case Addr.Failed(why) => ClientState(s"Failed: ${why.getMessage}")
          case a@Addr.Pending => ClientState(a.toString)
          case a@Addr.Neg => ClientState(a.toString)
        }
        entry.addr -> clientState
      }.toMap

    val response = Response()
    response.contentType = MediaType.Json + ";charset=UTF-8"
    response.contentString = mapper.writeValueAsString(entries)
    Future.value(response)
  }
}
