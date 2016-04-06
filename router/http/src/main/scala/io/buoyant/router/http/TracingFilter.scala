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
    Trace.recordBinary("http.method", req.method.toString)
    Trace.recordBinary("http.uri", req.uri)
    for (h <- req.host) {
      Trace.recordBinary("http.host", h)
    }
    recordMessage("req", req)

    service(req).onSuccess { rsp =>
      Trace.recordBinary("http.status", rsp.status.code)
      recordMessage("rsp", rsp)
    }
  }

  private[this] def recordMessage(prefix: String, msg: Message): Unit = {
    Trace.recordBinary(s"$prefix.http.version", msg.version.toString)
    for (length <- msg.contentLength) {
      Trace.recordBinary(s"$prefix.http.content-length", length)
    }
    for (t <- msg.contentType) {
      Trace.recordBinary(s"$prefix.http.content-type", t)
    }
    for (te <- msg.headerMap.get("transfer-encoding")) {
      Trace.recordBinary(s"$prefix.http.transfer-encoding", te)
    }
  }
}
