package io.buoyant.router.h2

import com.twitter.finagle.param
import com.twitter.finagle._
import com.twitter.finagle.buoyant.h2.{Request, Response}
import com.twitter.finagle.buoyant.h2.param.H2StreamClassifier
import io.buoyant.router.{PerDstPathFilter, PerDstPathStatsFilter}
import io.buoyant.router.context.h2.StreamClassifierCtx

/**
 * Like [[io.buoyant.router.PerDstPathStatsFilter]],
 * but specialized for H2 streams.
 */
object PerDstPathStreamStatsFilter {

  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module3[param.Stats, param.ExceptionStatsHandler, StreamStatsFilter.Param, ServiceFactory[Request, Response]] {
      override val role: Stack.Role = PerDstPathStatsFilter.role
      override val description = "Report request statistics for each logical destination"

      override def make(
        statsP: param.Stats,
        exHandlerP: param.ExceptionStatsHandler,
        statsFilterP: StreamStatsFilter.Param,
        next: ServiceFactory[Request, Response]
      ): ServiceFactory[Request, Response] =
        statsP match {
          case param.Stats(stats) if !stats.isNull =>
            val StreamStatsFilter.Param(timeUnit) = statsFilterP
            val H2StreamClassifier(classifier) =
              StreamClassifierCtx.current.getOrElse(H2StreamClassifier.param.default)
            val param.ExceptionStatsHandler(exHandler) = exHandlerP

            def mkScopedStatsFilter(path: Path): SimpleFilter[Request, Response] = {
              val name = path.show.stripPrefix("/")
              val scopedStats = stats.scope("service", name)
              new StreamStatsFilter(scopedStats, classifier, exHandler, timeUnit)
            }

            val filter = new PerDstPathFilter(mkScopedStatsFilter)
            filter.andThen(next)

          // can this actually be null? the HTTP1 `PerDstPathStatsFilter`
          // checks for this case, so i figured we ought to here as well...
          case _ => next

        }
    }
}
