package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.{Service, ServiceFactory, SimpleFilter, Stack, Stackable, param}
import com.twitter.finagle.stats.{Counter, StatsReceiver}
import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.util.{Duration, Future, Memoize, Return, Stopwatch, Throw, Try}

object StatusCodeStatsFilter {
  val role = Stack.Role("StatusCodeStatsFilter")

  def module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module1[param.Stats, ServiceFactory[Request, Response]] {
      val role = StatusCodeStatsFilter.role
      val description = "Records HTTP status code stats"

      def make(_stats: param.Stats, next: ServiceFactory[Request, Response]) = {
        val param.Stats(statsReceiver) = _stats
        if (statsReceiver.isNull) next
        else new StatusCodeStatsFilter(statsReceiver).andThen(next)
      }
    }
}

/**
 * Modified from com.twitter.finagle.http.filter.StatsFilter.
 *
 * Emits counters:
 *
 *   status/{code}
 *   status/{class [1-5]XX}
 *
 *   time/{code}/<histogram>
 *   time/{class [1-5]XX}}/<histogram>
 *   time/error/<histogram>
 */
class StatusCodeStatsFilter(stats: StatsReceiver)
  extends SimpleFilter[Request, Response] {

  private[this] val statusReceiver = stats.scope("status")
  private[this] val timeReceiver = stats.scope("time")

  private[this] val errorStat = timeReceiver.stat("error")

  private[this] val statusCodeCounters =
    Memoize { s: Status => statusReceiver.counter(s.code.toString) }

  private[this] val statusClasses = (1 to 5).map(c => s"${c}XX").toIndexedSeq
  private[this] val statusClassCounters = statusClasses.map(statusReceiver.counter(_))
  private[this] val statusClassStats = statusClasses.map(timeReceiver.stat(_))

  private[this] def count(rsp: Try[Response], duration: Long): Unit = rsp match {
    case Throw(_) =>
      errorStat.add(duration)

    case Return(rsp) =>
      statusCodeCounters(rsp.status).incr()

      val classIdx = (rsp.statusCode / 100) - 1
      statusClassCounters(classIdx).incr()
      statusClassStats(classIdx).add(duration)
  }

  def apply(request: Request, service: Service[Request, Response]): Future[Response] = {
    val stopwatch = Stopwatch.start()
    service(request).respond { ret =>
      count(ret, stopwatch().inMillis)
    }
  }

}
