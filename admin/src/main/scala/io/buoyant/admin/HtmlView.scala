package io.buoyant.admin

import com.twitter.finagle.http.{Response, MediaType}
import com.twitter.util.Future

trait HtmlView {
  def html(
    content: String,
    tailContent: String = "",
    csses: Seq[String] = Nil,
    javaScripts: Seq[String] = Nil,
    navbar: String = ""
  ): String

  def mkResponse(
    content: String,
    mediaType: String = MediaType.Html
  ): Future[Response]
}
