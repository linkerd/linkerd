package io.buoyant.admin

import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.finagle.http.{MediaType, Response, Request}
import com.twitter.util.Future

object StaticFilter extends SimpleFilter[Request, Response] {

  private[this] val contentTypes: Map[String, String] = Map(
    "css" -> "text/css",
    "gif" -> MediaType.Gif,
    "html" -> MediaType.Html,
    "jpeg" -> MediaType.Jpeg,
    "jpg" -> MediaType.Jpeg,
    "js" -> MediaType.Javascript,
    "json" -> MediaType.Json,
    "png" -> MediaType.Png,
    "svg" -> "image/svg+xml",
    "txt" -> MediaType.Txt
  )

  def apply(req: Request, svc: Service[Request, Response]) = {
    svc(req).flatMap { res =>
      val contentType = contentTypes.getOrElse(req.fileExtension, "application/octet-stream")
      res.contentType = contentType + ";charset=UTF-8"
      Future.value(res)
    }
  }
}
