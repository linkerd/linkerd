package io.buoyant.router.http

import com.twitter.finagle.Stack.Module0
import com.twitter.finagle.{Service, ServiceFactory, SimpleFilter, Stack}
import com.twitter.finagle.http.{Fields, Request, Response, Version}
import com.twitter.io.{BufReader}
import com.twitter.util.Future

/**
 * When Linkerd receives an HTTP/1.0 response that is terminated by closing the connection, the
 * response will not have a Content-Length header.  This causes Linkerd to interpret the response
 * as chunk encoded.  To avoid sending chunk encoded responses to HTTP/1.0 clients, we fully buffer
 * such responses and set the Content-Length header.
 */
object ContentLengthFilter {

  object module extends Module0[ServiceFactory[Request, Response]] {

    override def role: Stack.Role = Stack.Role("content-length")

    override def description: String = "Add a content-length header for HTTP/1.0 responses that need one"

    override def make(next: ServiceFactory[Request, Response]): ServiceFactory[Request, Response] =
      filter.andThen(next)
  }

  object filter extends SimpleFilter[Request, Response] {

    def hasTransferEncoding(response: Response): Boolean =
      response.headerMap.get(Fields.TransferEncoding) match {
        case None => false
        case Some(te) if te.toLowerCase == "identity" => false
        case Some(_) => true
      }

    override def apply(
      request: Request,
      service: Service[Request, Response]
    ): Future[Response] = {
      service(request).flatMap { response =>

        if (response.version == Version.Http10
          && !hasTransferEncoding(response)
          && response.contentLength.isEmpty) {
          BufReader.readAll(response.reader).map { buf =>
            response.setChunked(false)
            response.contentLength = buf.length
            response.content = buf
            response
          }
        } else {
          Future.value(response)
        }
      }
    }
  }
}
