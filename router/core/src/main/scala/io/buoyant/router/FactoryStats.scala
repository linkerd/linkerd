package io.buoyant.router

import com.twitter.finagle._
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.util._

object FactoryStats {
  val role = Stack.Role("FactoryStats")

  def module[Req, Rsp]: Stackable[ServiceFactory[Req, Rsp]] = {
    new Stack.Module1[param.Stats, ServiceFactory[Req, Rsp]] {
      val role = FactoryStats.role
      val description = "Tracks information about Service creation"
      def make(
        _stats: param.Stats,
        next: ServiceFactory[Req, Rsp]
      ) = {
        val param.Stats(stats) = _stats
        new FactoryStats(stats, next)
      }
    }
  }
}

class FactoryStats[Req, Rsp](
  stats: StatsReceiver,
  self: ServiceFactory[Req, Rsp]
) extends ServiceFactoryProxy[Req, Rsp](self) {
  private[this] val create = stats.stat("create_ms")
  private[this] val success = stats.counter("create_success")
  private[this] val failures = stats.counter("create_failure")

  override def apply(conn: ClientConnection): Future[Service[Req, Rsp]] = {
    val start = Stopwatch.start()
    self(conn).respond { res =>
      res match {
        case Return(_) => success.incr()
        case Throw(_) => failures.incr()
      }
      create.add(start().inMillis)
    }
  }
}
