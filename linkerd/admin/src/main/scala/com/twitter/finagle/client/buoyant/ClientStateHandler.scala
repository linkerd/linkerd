package com.twitter.finagle.client.buoyant

import com.twitter.finagle.{Addr, Address, Service}
import com.twitter.finagle.client.ClientRegistry
import com.twitter.finagle.http.{MediaType, Request, Response}
import com.twitter.finagle.loadbalancer.LoadBalancerFactory
import com.twitter.util.Future
import io.buoyant.config.Parser

class ClientStateHandler extends Service[Request, Response] {

  private[this] val mapper = Parser.jsonObjectMapper(Nil)

  override def apply(request: Request): Future[Response] = {
    val entries = ClientRegistry.registrants.map { entry =>
      val LoadBalancerFactory.Dest(va) = entry.params[LoadBalancerFactory.Dest]
      va.changes.toFuture().map(entry.addr -> _)
    }
    Future.collect(entries.toSeq).map { entries =>
      val clientState = entries.map {
        case (client, addr) =>
          val state = addr match {
            case Addr.Bound(addresses, meta) =>
              addresses.map {
                case Address.Inet(isa, _) => isa.getHostString
                case a => a.toString
              }.toSeq
            case Addr.Failed(why) => s"Failed: ${why.getMessage}"
            case a@Addr.Pending => a.toString
            case a@Addr.Neg => a.toString
          }
          client -> state
      }.toMap

      val response = Response()
      response.contentType = MediaType.Json + ";charset=UTF-8"
      response.contentString = mapper.writeValueAsString(clientState)
      response
    }
  }
}
