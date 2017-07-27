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

  def module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module2[param.Stats, StreamStatsFilter.Param, ServiceFactory[Request, Response]] {
      val role: Stack.Role = PerDstPathStatsFilter.role
      val description =
        s"${PerDstPathStatsFilter.role}, using H2 stream classification"

      override def make(
        statsP: param.Stats,
        statsFilterP: StreamStatsFilter.Param,
        next: ServiceFactory[Request, Response]
      ): ServiceFactory[Request, Response] =
        statsP match {
          case param.Stats(stats) if !stats.isNull =>
            val StreamStatsFilter.Param(timeUnit) = statsFilterP
            val H2StreamClassifier(classifier) =
              StreamClassifierCtx.current.getOrElse(H2StreamClassifier.param.default)

            def mkScopedStatsFilter(path: Path): Filter[Request, Response, Request, Response] = {
              val name = path.show.stripPrefix("/")
              val scopedStats = stats.scope("service", name)
              new StreamStatsFilter(scopedStats, classifier, timeUnit)
            }

            val filter = new PerDstPathFilter(mkScopedStatsFilter)
            filter.andThen(next)

          // if the stats receiver is the `NullReceiver`, don't make a filter.
          case _ => next

        }
    }
}
