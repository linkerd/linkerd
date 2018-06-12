package io.buoyant.linkerd.protocol.h2

import com.twitter.finagle.buoyant.h2.{Frame, Method, Request, Response, Status, Stream}
import com.twitter.finagle._
import com.twitter.finagle.buoyant.h2.Frame.{Data, Trailers}
import com.twitter.finagle.client.Transporter.EndpointAddr
import com.twitter.finagle.http.util.StringUtil
import com.twitter.finagle.naming.buoyant.DstBindingFactory
import com.twitter.util._
import io.buoyant.linkerd.RouterContextFormatter
import io.buoyant.router.RoutingFactory.BaseDtab
import io.buoyant.router.context.{DstBoundCtx, DstPathCtx}
import io.buoyant.router.{RouterLabel, RoutingFactory}

/**
 * Intercepts H2 TRACE requests with a max-forwards and l5d-add-context header.
 * Returns identification, delegation, and service address information
 * for a given router as well as any other TRACE responses received from downstream
 * services. Router information is added to the end of an H2 stream
 *
 * A router that receives a tracer request with a max-forwards
 * greater than 0 decrements the max-forwards header by 1 and
 * forwards the tracer request to a downstream service that may perform
 * additional processing of the request.
 *
 * @param endpoint the endpoint address of a downstream H2 service identified by a router
 * @param namers namers used to evaluate a service name
 * @param dtab base dtab used by the router
 * @param routerLabel name of router
 */
class H2DiagnosticTracer(
  endpoint: EndpointAddr,
  namers: DstBindingFactory.Namer,
  dtab: BaseDtab,
  routerLabel: String
) extends SimpleFilter[Request, Response] {

  private[this] val MaxForwards = "max-forwards"
  private[this] val AddRouterContextHeader = "l5d-add-context"

  private[this] def getRequestTraceResponse(resp: Response, stopwatch: Stopwatch.Elapsed): Future[Response] = {
    val routerCtxF = RouterContextFormatter.formatCtx(
      routerLabel,
      stopwatch,
      DstPathCtx.current,
      DstBoundCtx.current,
      endpoint,
      namers,
      dtab)

    routerCtxF.map { ctx =>
      // format current router context
      val content = s"\n${ctx.trim}\n"

      if (resp.stream.isEmpty) {
        // add router context data frame to empty stream
        Response(resp.headers, Stream.const(Data.eos(content)))
      } else {
        val newStream = resp.stream.flatMap {
          // read every frame of a stream
          case trailerFrame: Trailers =>
            // on trailers frame, prepend a data frame containing router context to trailer
            val routerCtxFrame = Data(content, eos = false)
            Seq(routerCtxFrame, trailerFrame)
          case dataFrame: Data if dataFrame.isEnd =>
            // on data frame with EOS flag to true, prepend new data frame with router context to
            // EOS data frame
            val newDataFrame = Data.copy(dataFrame, eos = false)
            val routerCtxFrame = Data.eos(content)
            Seq(newDataFrame, routerCtxFrame)
          case frame =>
            // do nothing if we find a frame that doesn't indicate the end of a stream
            Seq(frame)
        }
        Response(resp.headers, newStream)
      }
    }

  }

  override def apply(
    request: Request,
    service: Service[Request, Response]
  ): Future[Response] = {
    val maxForwards = Try(request.headers.get(MaxForwards).map(_.toInt))
    val isAddRouterCtx = request.headers.get(AddRouterContextHeader) match {
      case Some(v) => StringUtil.toBoolean(v)
      case None => false
    }

    (request.method, maxForwards, isAddRouterCtx) match {
      case (Method.Trace, Throw(_: NumberFormatException), _) =>
        // MaxForwards is unparsable send a bad request response
        Future.value(Response(Status.BadRequest, Stream.const(s"Invalid value for $MaxForwards header")))
      case (Method.Trace, Return(Some(0)), true) =>
        // MaxForwards header is 0 and the l5d-add-context header is present
        // returns a response with router context
        val stopwatch = Stopwatch.start()
        getRequestTraceResponse(Response(Status.Ok, Stream.empty), stopwatch)
      case (Method.Trace, Return(Some(0)), false) =>
        // returns a response with no router context
        Future.value(Response(Status.Ok, Stream.empty))
      case (Method.Trace, Return(Some(num)), true) if num > 0 =>
        // Decrement Max-Forwards header. forward H2 request downstream
        val stopwatch = Stopwatch.start()
        val req1 = request.dup()
        req1.headers.set(MaxForwards, (num - 1).toString)
        service(req1).flatMap(getRequestTraceResponse(_, stopwatch))
      case (Method.Trace, Return(Some(num)), false) if num > 0 =>
        // Decrement MaxForwards header.
        // Forward H2 request downstream without adding router context to response
        val req1 = request.dup()
        req1.headers.set(MaxForwards, (num - 1).toString)
        service(req1)
      case (Method.Trace, Return(None), true) =>
        // No limit to MaxForwards just send the request downstream
        val stopwatch = Stopwatch.start()
        service(request).flatMap(getRequestTraceResponse(_, stopwatch))
      case (_, _, _) =>
        // Do nothing. Simply route request.
        service(request)
    }
  }
}

object H2DiagnosticTracer {
  val module: Stackable[ServiceFactory[Request, Response]] = {
    new Stack.Module4[EndpointAddr, RoutingFactory.BaseDtab, DstBindingFactory.Namer, RouterLabel.Param, ServiceFactory[Request, Response]] {

      override def role: Stack.Role = Stack.Role("H2DiagnosticTracer")

      override def description: String = "Intercepts to respond with useful client destination info"

      override def make(
        endpoint: EndpointAddr,
        dtab: BaseDtab,
        interpreter: DstBindingFactory.Namer,
        label: RouterLabel.Param,
        next: ServiceFactory[Request, Response]
      ): ServiceFactory[Request, Response] =
        new H2DiagnosticTracer(endpoint, interpreter, dtab, label.label).andThen(next)
    }
  }
}
