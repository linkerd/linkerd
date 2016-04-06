package io.buoyant.router.http

import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.finagle.http.{Message, Request, Response}
import com.twitter.finagle.tracing.Trace

/**
 * Annotates HTTP method, uri, and status code, content-type, and content-length.
 */
object TracingFilter extends SimpleFilter[Request, Response] {

  def apply(req: Request, service: Service[Request, Response]) = {
    Trace.recordRpc(req.method.toString)
    // http.uri is used here for consistency with finagle-http's tracing filter
    Trace.recordBinary("http.uri", req.uri)
    Trace.recordBinary("http.req.method", req.method.toString)
    for (h <- req.host) {
      Trace.recordBinary("http.req.host", h)
    }
    recordMessage("req", req)

    service(req).onSuccess { rsp =>
      Trace.recordBinary("http.rsp.status", rsp.status.code)
      recordMessage("rsp", rsp)
    }
  }

  private[this] def recordMessage(scope: String, msg: Message): Unit = {
    Trace.recordBinary(s"http.$scope.version", msg.version.toString)
    for (length <- msg.contentLength) {
      Trace.recordBinary(s"http.$scope.content-length", length)
    }
    for (t <- msg.contentType) {
      Trace.recordBinary(s"http.$scope.content-type", t)
    }
    for (te <- msg.headerMap.get("transfer-encoding")) {
      Trace.recordBinary(s"http.$scope.transfer-encoding", te)
    }
  }
}
