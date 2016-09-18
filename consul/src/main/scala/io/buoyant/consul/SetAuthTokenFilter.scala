package io.buoyant.consul

import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.{Service, SimpleFilter}

class SetAuthTokenFilter(token: String) extends SimpleFilter[Request, Response] {

  def apply(req: Request, svc: Service[Request, Response]) = {
    req.headerMap.set("X-Consul-Token", token)
    svc(req)
  }
}
