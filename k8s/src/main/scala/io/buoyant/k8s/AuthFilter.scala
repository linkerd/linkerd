package io.buoyant.k8s

import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.{Service, SimpleFilter}

class AuthFilter(token: String) extends SimpleFilter[Request, Response] {
  def apply(req: Request, service: Service[Request, Response]) = {
    req.headerMap("Authorization") = s"Bearer $token"
    service(req)
  }
}
