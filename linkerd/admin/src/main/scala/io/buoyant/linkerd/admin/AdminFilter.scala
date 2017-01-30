package io.buoyant.linkerd.admin

import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.finagle.http.{MediaType, Request, Response}
import com.twitter.util.Future

/**
 * This filter builds a linkerd admin page by wrapping an html content blob with the linkerd
 * admin chome (navbar, stylesheets, etc.)
 */
class AdminFilter(adminHandler: AdminHandler, css: Seq[String] = Nil) extends SimpleFilter[Request, Response] {
  override def apply(
    request: Request,
    service: Service[Request, Response]
  ): Future[Response] = {
    service(request).map { rsp =>
      if (rsp.contentType.contains(MediaType.Html))
        rsp.contentString = adminHandler.html(rsp.contentString, csses = css)
      rsp
    }
  }
}
