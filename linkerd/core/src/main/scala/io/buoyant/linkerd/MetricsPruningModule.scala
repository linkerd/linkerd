package io.buoyant.linkerd

import com.twitter.finagle._
import com.twitter.logging.Logger
import com.twitter.util.{Future, Time}
import io.buoyant.telemetry.MetricsTreeStatsReceiver

object MetricsPruningModule {

  val role = Stack.Role("MetricsPruning")

  val log = Logger()

  def module[Req, Rep]: Stackable[ServiceFactory[Req, Rep]] = new Stack.Module2[param.Stats, param.Label, ServiceFactory[Req, Rep]] {
    val role: Stack.Role = MetricsPruningModule.role

    val description: String = "Prune the metrics for this ServiceFactory when it is closed"

    def make(
      stats: param.Stats,
      label: param.Label,
      next: ServiceFactory[Req, Rep]
    ): ServiceFactory[Req, Rep] = {
      stats match {

        // what's a little coupling between friends?
        case param.Stats(statsReceiver: MetricsTreeStatsReceiver) =>
          new ServiceFactoryProxy[Req, Rep](next) {
            override def close(deadline: Time): Future[Unit] = {
              self.close(deadline).respond { _ =>
                log.info(s"Reaping ${label.label}")
                statsReceiver.prune()
              }
            }
          }
        case _ => next
      }
    }
  }
}