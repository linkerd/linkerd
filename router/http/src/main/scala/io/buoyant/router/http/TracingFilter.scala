package io.buoyant.router.http

import com.twitter.conversions.storage._
import com.twitter.finagle.{Service, ServiceFactory, SimpleFilter, Stack, Stackable, param}
import com.twitter.finagle.client.StackClient
import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.finagle.tracing.Trace

object TracingFilter {
  val role = StackClient.Role.protoTracing
  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module1[param.Tracer, ServiceFactory[Request, Response]] {
      val role = TracingFilter.role
      val description = "Traces HTTP-specific request metadata"
      def make(_tracer: param.Tracer, next: ServiceFactory[Request, Response]) = {
        val param.Tracer(tracer) = _tracer
        if (tracer.isNull) next
        else (new TracingFilter).andThen(next)
      }
    }

  val TransferEncoding = "transfer-encoding"

  val MaxBodySize = 64 * 1000 // 64KB-ish
}

/**
 * Annotates HTTP method, uri, and status code, content-type, and content-length.
 */
class TracingFilter extends SimpleFilter[Request, Response] {
  import TracingFilter.{TransferEncoding, MaxBodySize}

  def apply(req: Request, service: Service[Request, Response]) = {
    recordRequest(req)
    service(req).onSuccess(recordResponseFn)
  }

  private[this] def recordRequest(req: Request): Unit =
    if (Trace.isActivelyTracing) {
      Trace.recordRpc(req.method.toString)
      // http.uri is used here for consistency with finagle-http's tracing filter
      Trace.recordBinary("http.uri", req.uri)
      Trace.recordBinary("http.req.method", req.method.toString)
      req.host match {
        case Some(h) => Trace.recordBinary("http.req.host", h)
        case None =>
      }
      Trace.recordBinary("http.req.version", req.version.toString)
      req.contentLength match {
        case Some(l) => Trace.recordBinary("http.req.content-length", l)
        case None =>
      }
      req.contentType match {
        case Some(t) => Trace.recordBinary("http.req.content-type", t)
        case None =>
      }
      req.headerMap.get(TransferEncoding) match {
        case Some(te) => Trace.recordBinary("http.req.transfer-encoding", te)
        case None =>
      }
    }

  private[this] def recordResponse(rsp: Response): Unit =
    if (Trace.isActivelyTracing) {
      Trace.recordBinary("http.rsp.status", rsp.status.code)
      Trace.recordBinary("http.rsp.version", rsp.version.toString)
      if (500 <= rsp.statusCode && rsp.statusCode < 600) {
        // XXX this doesn't work with zipkin, which will just ignore
        // this annotation since it has a Buf body. We should revisit
        // this later...
        Trace.recordBinary("http.rsp.body", rsp.content.slice(0, MaxBodySize))
      }
      rsp.contentLength match {
        case Some(l) => Trace.recordBinary("http.rsp.content-length", l)
        case None =>
      }
      rsp.contentType match {
        case Some(ct) => Trace.recordBinary("http.rsp.content-type", ct)
        case None =>
      }
      rsp.headerMap.get(TransferEncoding) match {
        case Some(te) => Trace.recordBinary("http.rsp.transfer-encoding", te)
        case None =>
      }
    }

  private[this] val recordResponseFn: Response => Unit = recordResponse _
}
