package io.buoyant.linkerd.protocol.h2

import com.twitter.finagle.buoyant.h2.{Frame, Method, Request, Response, Status, Stream}
import com.twitter.finagle._
import com.twitter.finagle.buoyant.h2.Frame.{Data, Trailers}
import com.twitter.finagle.client.Transporter.EndpointAddr
import com.twitter.finagle.http.util.StringUtil
import com.twitter.finagle.naming.buoyant.DstBindingFactory
import com.twitter.util._
import io.buoyant.linkerd.RouterContextBuilder
import io.buoyant.router.RoutingFactory.BaseDtab
import io.buoyant.router.context.{DstBoundCtx, DstPathCtx}
import io.buoyant.router.{RouterLabel, RoutingFactory}

class H2RequestActiveTracer(
  endpoint: EndpointAddr,
  namers: DstBindingFactory.Namer,
  dtab: BaseDtab,
  routerLabel: String
) extends SimpleFilter[Request, Response] {

  private[this] val MaxForwards = "max-forwards"
  private[this] val AddRouterContextHeader = "l5d-add-context"
  private[this] def getRequestTraceResponse(
    resp: Response,
    stopwatch: Stopwatch.Elapsed
  ) = {
    val routerCtxF = RouterContextBuilder(
      routerLabel,
      stopwatch,
      DstPathCtx.current,
      DstBoundCtx.current,
      endpoint,
      namers,
      dtab
    )

    routerCtxF.map { ctx =>
      val content = s"""|
                        |${ctx.formatRouterContext.trim}
                        |""".stripMargin
      if (resp.stream.isEmpty) {
        Response(resp.headers, Stream.const(Frame.Data(content, eos = true)))
      } else {
        val newStream = resp.stream.flatMap {
          case trailerFrame: Trailers =>
            val routerCtxFrame = Frame.Data(content, eos = false)
            Seq(routerCtxFrame, trailerFrame)
          case dataFrame: Data if dataFrame.isEnd =>
            val newDataFrame = Frame.Data.copy(dataFrame, eos = false)
            val routerCtxFrame = Data(content, eos = true)
            Seq(newDataFrame, routerCtxFrame, Trailers())
          case frame =>
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
        Future.value(Response(Status.BadRequest, Stream.empty))
      case (Method.Trace, Return(Some(0)), true) =>
        val stopwatch = Stopwatch.start()
        getRequestTraceResponse(Response(request.headers, Stream.empty), stopwatch)
      case (Method.Trace, Return(Some(0)), false) =>
        Future.value(Response(Status.Ok, Stream.empty))
      case (Method.Trace, Return(Some(num)), true) if num > 0 =>
        val stopwatch = Stopwatch.start()
        request.headers.set(MaxForwards, (num - 1).toString)
        service(request).flatMap(getRequestTraceResponse(_, stopwatch)).ensure {
          request.headers.set(MaxForwards, (num - 1).toString)
        }
      case (Method.Trace, Return(Some(num)), false) if num > 0 =>
        request.headers.set(MaxForwards, (num - 1).toString)
        service(request).ensure {
          request.headers.set(MaxForwards, num.toString); ()
        }
      case (Method.Trace, Return(None), true) =>
        val stopwatch = Stopwatch.start()
        service(request).flatMap(getRequestTraceResponse(_, stopwatch))
      case (_, _, _) =>
        service(request)
    }
  }
}

object H2RequestActiveTracer {
  val module: Stackable[ServiceFactory[Request, Response]] = {
    new Stack.Module4[EndpointAddr, RoutingFactory.BaseDtab, DstBindingFactory.Namer, RouterLabel.Param, ServiceFactory[Request, Response]] {

      override def role: Stack.Role = Stack.Role("H2RequestEvaluator")

      override def description: String = "Intercepts to respond with useful client destination info"

      override def make(
        endpoint: EndpointAddr,
        dtab: BaseDtab,
        interpreter: DstBindingFactory.Namer,
        label: RouterLabel.Param,
        next: ServiceFactory[Request, Response]
      ): ServiceFactory[Request, Response] =
        new H2RequestActiveTracer(endpoint, interpreter, dtab, label.label).andThen(next)
    }
  }
}
