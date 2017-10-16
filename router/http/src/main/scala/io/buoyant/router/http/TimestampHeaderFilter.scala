package io.buoyant.router.http

import com.twitter.finagle.{Filter, Service}
import com.twitter.finagle.http.{Request, Response}
import com.twitter.util.Time

/**
 * Adds a timestamp header to a message.
 *
 * This is intended to be used for the New Relic request queue's
 * `x-request-start` header, but might be useful for other purposes?
 */
object TimestampHeaderFilter {

 def filter(header: String): Filter[Request, Response, Request, Response] =
   (req: Request, svc: Service[Request, Response]) => {
     val reqT = Time.now
     svc(req).map { rsp =>
       rsp.headerMap += header -> reqT.inMillis.toString
       rsp
     }
   }

}
