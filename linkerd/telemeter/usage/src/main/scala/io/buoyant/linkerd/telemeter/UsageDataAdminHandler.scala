package io.buoyant.linkerd.telemeter

import com.twitter.finagle.Service
import com.twitter.finagle.http.{MediaType, Request, Response}
import com.twitter.util.Future

class UsageDataAdminHandler extends Service[Request, Response] {

  override def apply(req: Request): Future[Response] = {
    req.response.contentString =
      s"""
       |<div class="container main">
       |  <div class="usage-content"></div>
       |</div>
     """.stripMargin
    req.response.contentType = MediaType.Html
    Future.value(req.response)
  }
}
