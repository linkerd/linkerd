package io.buoyant.admin

import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.finagle.http.{Request, Response}
import com.twitter.util.{Await, Future}

class StyleOverrideFilter extends SimpleFilter[Request, Response] {
  // inject some js and css into the Logging page so we can style it ourselves

  val stylesAndJavascripts =
    s"""
    <link type="text/css" href="/files/css/logger-overrides.css" rel="stylesheet"/>
    <link type="text/css" href="/files/css/dashboard.css" rel="stylesheet"/>
    <script src="/files/js/logger-overrides.js"></script>
      """

  def apply(req: Request, svc: Service[Request, Response]) = {
    svc(req).map { response =>
      response.setContentString(response.contentString + stylesAndJavascripts)
      response
    }
  }
}
