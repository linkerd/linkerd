package io.buoyant.router.http.stats

import com.twitter.finagle.http.Response
import com.twitter.finagle.stats.{Counter, Stat, StatsReceiver}
import com.twitter.util.Duration

object ServiceToServiceStats {
  def mk(statsReceiver: StatsReceiver, statusCode: Int): ServiceToServiceStats = {
    val statusClass = s"${statusCode / 100}XX"
    ServiceToServiceStats(
      requestCount = statsReceiver.counter("requests"),
      statusClassCount = statsReceiver.scope("status").counter(statusClass),
      requestTime = statsReceiver.stat("request_latency_ms")
    )
  }
}

case class ServiceToServiceStats(requestCount: Counter, statusClassCount: Counter, requestTime: Stat) {
  def count(response: Response, duration: Duration): Unit = {
    requestCount.incr()
    statusClassCount.incr()

    val durationMs = duration.inMilliseconds
    requestTime.add(durationMs.toFloat)
  }
}
