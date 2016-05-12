package io.buoyant.router.http

import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.tracing.Trace

/**
 * Annotates HTTP method, uri, and status code, content-type, and content-length.
 */
object TracingFilter extends SimpleFilter[Request, Response] {

  def apply(req: Request, service: Service[Request, Response]) = {
    recordRequest(req)
    service(req).onSuccess(recordResponse)
  }

  private[this] def recordRequest(req: Request): Unit = {
    Trace.recordRpc(req.method.toString)
    // http.uri is used here for consistency with finagle-http's tracing filter
    Trace.recordBinary("http.uri", req.uri)
    Trace.recordBinary("http.req.method", req.method.toString)
    req.host.foreach(Trace.recordBinary("http.req.host", _))
    Trace.recordBinary("http.req.version", req.version.toString)
    req.contentLength.foreach(Trace.recordBinary("http.req.content-length", _))
    req.contentType.foreach(Trace.recordBinary("http.req.content-type", _))
    req.headerMap.get("transfer-encoding").foreach { te =>
      Trace.recordBinary("http.req.transfer-encoding", te)
    }
  }

  private[this] def recordResponse(rsp: Response): Unit = {
    Trace.recordBinary("http.rsp.status", rsp.status.code)
    Trace.recordBinary("http.rsp.version", rsp.version.toString)
    rsp.contentLength.foreach(Trace.recordBinary("http.rsp.content-length", _))
    if (rsp.status.code >= 500 && rsp.status.code < 600) {
      Trace.recordBinary("http.rsp.body", rsp.content.slice(0, 64 * 1000 /*64kb*/ ))
    }
    rsp.contentType.foreach(Trace.recordBinary("http.rsp.content-type", _))
    rsp.headerMap.get("transfer-encoding").foreach { te =>
      Trace.recordBinary("http.rsp.transfer-encoding", te)
    }
  }
}
