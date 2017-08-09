package io.buoyant.router.h2

import com.twitter.finagle.param
import com.twitter.finagle._
import com.twitter.finagle.buoyant.h2.{Request, Response}
import com.twitter.finagle.buoyant.h2.param.H2Classifier
import io.buoyant.router.{PerDstPathFilter, PerDstPathStatsFilter}
import io.buoyant.router.context.h2.H2ClassifierCtx

/**
 * Like io.buoyant.router.PerDstPathStatsFilter,
 * but specialized for H2 streams.
 */
object PerDstPathStreamStatsFilter {

  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module3[param.Stats, param.ExceptionStatsHandler, StreamStatsFilter.Param, ServiceFactory[Request, Response]] {
      val role: Stack.Role = PerDstPathStatsFilter.role
      val description =
        s"${PerDstPathStatsFilter.role}, using H2 stream classification"

      override def make(
        statsP: param.Stats,
        exHandlerP: param.ExceptionStatsHandler,
        statsFilterP: StreamStatsFilter.Param,
        next: ServiceFactory[Request, Response]
      ): ServiceFactory[Request, Response] =
        statsP match {
          case param.Stats(stats) if !stats.isNull =>
            val StreamStatsFilter.Param(timeUnit) = statsFilterP
            val param.ExceptionStatsHandler(exHandler) = exHandlerP

            def mkScopedStatsFilter(path: Path): SimpleFilter[Request, Response] = {
              val name = path.show.stripPrefix("/")
              val scopedStats = stats.scope("service", name)
              val H2Classifier(classifier) =
                H2ClassifierCtx.current.getOrElse(H2Classifier.param.default)
              new StreamStatsFilter(scopedStats, classifier, exHandler, timeUnit)
            }

            val filter = new PerDstPathFilter(mkScopedStatsFilter)
            filter.andThen(next)

          // if the stats receiver is the `NullReceiver`, don't make a filter.
          case _ => next

        }
    }
}
