package io.buoyant.admin

import com.twitter.finagle.http.{Response, MediaType}
import com.twitter.util.Future

trait HtmlView {
  def html(
    content: String,
    tailContent: String = "",
    csses: Seq[String] = Nil,
    navHighlight: String = "",
    showRouterDropdown: Boolean = false
  ): String

  def mkResponse(
    content: String,
    mediaType: String = MediaType.Html
  ): Future[Response]
}
