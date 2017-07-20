package io.buoyant.namer.marathon

import com.twitter.finagle.{http, SimpleFilter, Service}
import com.twitter.util.Future
import io.buoyant.marathon.v2.Api

class BasicAuthenticatorFilter(http_auth_token: String) extends SimpleFilter[http.Request, http.Response] {

  def apply(req: http.Request, svc: Service[http.Request, http.Response]): Future[http.Response] = {
    req.headerMap.set("Authorization", s"Basic $http_auth_token")
    svc(req)
  }
}