package io.buoyant.linkerd.protocol.http

import com.twitter.finagle._
import com.twitter.finagle.client.Transporter.EndpointAddr
import com.twitter.finagle.http.Fields.MaxForwards
import com.twitter.finagle.http.{Status, _}
import com.twitter.finagle.http.util.StringUtil
import com.twitter.finagle.naming.buoyant.DstBindingFactory
import com.twitter.util._
import io.buoyant.linkerd.RouterContextFormatter
import io.buoyant.router.{RouterLabel, RoutingFactory}
import io.buoyant.router.RoutingFactory.BaseDtab
import io.buoyant.router.context.{DstBoundCtx, DstPathCtx}

/**
 * Intercepts Http TRACE requests with a Max-Forwards and l5d-add-context header.
 * Returns identification, delegation, and service address information
 * for a given router as well as any other TRACE responses received from downstream
 * services.
 *
 * A router that receives a tracer request with a Max-Forwards
 * greater than 0 decrements the Max-Forwards header by 1 and
 * forwards the tracer request to a downstream service that may perform
 * additional processing of the request.
 *
 * @param endpoint the endpoint address of a downstream service identified by a router
 * @param namers namers used to evaluate a service name
 * @param dtab base dtab used by the router
 * @param routerLabel name of router
 */
class DiagnosticTracer(
  endpoint: EndpointAddr,
  namers: DstBindingFactory.Namer,
  dtab: BaseDtab,
  routerLabel: String
) extends SimpleFilter[Request, Response] {
  private[this] val AddRouterContextHeader = "l5d-add-context"

  private[this] def ChunkedResponsePlaceholderMsg(headers: HeaderMap, status: Status) = {
    val headerStr = headers.map {
      case (headerKey, headerValue) => s"$headerKey: $headerValue"
    }.mkString("\n")

    s"$headerStr\nHTTP ${status.code} - ${status.reason}\nDiagnostic trace encountered chunked response. Response content discarded."
  }

  private[this] def getRequestTraceResponse(resp: Response, stopwatch: Stopwatch.Elapsed) = {

    val routerCtxF = RouterContextFormatter.formatCtx(
      routerLabel,
      stopwatch,
      DstPathCtx.current,
      DstBoundCtx.current,
      endpoint,
      namers,
      dtab
    )

    routerCtxF.map { ctx =>
      if (resp.isChunked) {
        resp.reader.discard()
        resp.setChunked(false)
        resp.contentString = ChunkedResponsePlaceholderMsg(resp.headerMap, resp.status)
      }
      val content = s"${resp.contentString.trim}\n\n$ctx"
      resp.contentString = content
      //We set the content length of the response in order to view the content string in the response
      // entirely
      resp.contentLength = resp.content.length
      resp
    }
  }

  override def apply(
    req: Request,
    svc: Service[Request, Response]
  ): Future[Response] = {

    val maxForwards = Try(req.headerMap.get(MaxForwards).map(_.toInt))
    val isAddRouterCtx = req.headerMap.get(AddRouterContextHeader) match {
      case Some(v) => StringUtil.toBoolean(v)
      case None => false
    }

    (req.method, maxForwards, isAddRouterCtx) match {
      case (Method.Trace, Throw(_: NumberFormatException), _) =>
        // Max-Forwards header is unparsable
        val resp = Response(Status.BadRequest)
        resp.contentString = s"Invalid value for $MaxForwards header"
        Future.value(resp)
      case (Method.Trace, Return(Some(0)), true) =>
        // Max-Forwards header is 0 and the l5d-add-context header is present
        // returns a response with router context
        val stopwatch = Stopwatch.start()
        getRequestTraceResponse(Response(req), stopwatch)
      case (Method.Trace, Return(Some(0)), false) =>
        // returns a response with no router context
        Future.value(Response(req))
      case (Method.Trace, Return(Some(num)), true) if num > 0 =>
        // Decrement Max-Forwards header
        req.headerMap.set(MaxForwards, (num - 1).toString)
        // Forward request downstream and add router context to response
        val stopwatch = Stopwatch.start()
        svc(req).flatMap(getRequestTraceResponse(_, stopwatch)).ensure {
          req.headerMap.set(MaxForwards, num.toString); ()
        }
      case (Method.Trace, Return(Some(num)), false) if num > 0 =>
        req.headerMap.set(MaxForwards, (num - 1).toString)
        // Forward requests without adding router context to response
        svc(req).ensure {
          req.headerMap.set(MaxForwards, num.toString); ()
        }
      case (Method.Trace, Return(None), true) =>
        val stopwatch = Stopwatch.start()
        svc(req).flatMap(getRequestTraceResponse(_, stopwatch))
      case (_, _, _) => svc(req)
    }
  }
}

object DiagnosticTracer {

  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module4[EndpointAddr, RoutingFactory.BaseDtab, DstBindingFactory.Namer, RouterLabel.Param, ServiceFactory[Request, Response]] {

      override def role: Stack.Role = Stack.Role("DiagnosticTracer")

      override def description: String = "Intercepts to respond with useful client destination info"

      override def make(
        endpoint: EndpointAddr,
        dtab: BaseDtab,
        interpreter: DstBindingFactory.Namer,
        label: RouterLabel.Param,
        next: ServiceFactory[Request, Response]
      ): ServiceFactory[Request, Response] =
        new DiagnosticTracer(endpoint, interpreter, dtab, label.label).andThen(next)
    }
}

