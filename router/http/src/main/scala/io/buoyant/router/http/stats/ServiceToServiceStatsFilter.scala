package io.buoyant.router.http.stats

import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.http.Status.InternalServerError
import com.twitter.finagle.http.{Method, Request, Response}
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.util._
import io.buoyant.router.context.DstPathCtx

class ServiceToServiceStatsFilter(statsReceiver: StatsReceiver) extends SimpleFilter[Request, Response] {
  private val serviceToServiceStats = Memoize[(Src, Dst.Path, Method, Int), ServiceToServiceStats] {
    case (src, dst, method, statusCode) =>
      val scopedStatsReceiver =
        statsReceiver.scope("route", src.name, dst.path.show, method.toString.toUpperCase)
      ServiceToServiceStats.mk(scopedStatsReceiver, statusCode)
  }

  override def apply(request: Request, service: Service[Request, Response]): Future[Response] = {
    val elapsed = Stopwatch.start()
    service(request).respond {
      case Return(response) => record(request, response, elapsed())
      case Throw(e)         => record(request, Response(InternalServerError), elapsed())
    }
  }

  private def record(request: Request, response: Response, duration: Duration) = {
    val svcToSvcStats = for {
      src <- Src.HeaderSrcIdentifier(request)
      dst <- DstPathCtx.current
    } yield serviceToServiceStats((src, dst, request.method, response.statusCode))

    svcToSvcStats.foreach(_.count(response, duration))
  }
}
