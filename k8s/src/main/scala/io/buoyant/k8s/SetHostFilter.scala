package io.buoyant.k8s

import java.net.InetSocketAddress

import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.{Service, SimpleFilter}

class SetHostFilter(hostname: String, port: Int) extends SimpleFilter[Request, Response] {
  def this(addr: InetSocketAddress) = this(addr.getHostString, addr.getPort)

  val host: String = port match {
    case 80 | 443 => hostname
    case port => s"$hostname:$port"
  }

  def apply(req: Request, svc: Service[Request, Response]) = {
    req.host = host
    svc(req)
  }
}
