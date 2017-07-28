package io.buoyant.router.h2

import com.twitter.finagle.param
import com.twitter.finagle._
import com.twitter.finagle.buoyant.h2.{Request, Response}
import com.twitter.finagle.buoyant.h2.param.H2Classifier
import io.buoyant.router.{LocalClassifierStatsFilter, PerDstPathFilter}
import io.buoyant.router.context.h2.H2ClassifierCtx

/**
 * Like [[io.buoyant.router.LocalClassifierStatsFilter]],
 * but specialized for H2 streams.
 */
object LocalClassifierStreamStatsFilter {

  def module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module2[param.Stats, StreamStatsFilter.Param, ServiceFactory[Request, Response]] {
      val role: Stack.Role = LocalClassifierStatsFilter.role
      val description = "Report request statistics using local H2 stream classifier"

      override def make(
        statsP: param.Stats,
        statsFilterP: StreamStatsFilter.Param,
        next: ServiceFactory[Request, Response]
      ): ServiceFactory[Request, Response] =
        statsP match {
          case param.Stats(stats) if !stats.isNull =>
            val StreamStatsFilter.Param(timeUnit) = statsFilterP

            // We memoize on the dst path.  The assumes that the response classifier for a dst path
            // never changes.
            def mkClassifiedStatsFilter(path: Path): SimpleFilter[Request, Response] = {
              val H2Classifier(classifier) =
                H2ClassifierCtx.current.getOrElse(H2Classifier.param.default)
              new StreamStatsFilter(stats, classifier, timeUnit)
            }

            val filter = new PerDstPathFilter(mkClassifiedStatsFilter)
            filter.andThen(next)

          // if the stats receiver is the `NullReceiver`, don't make a filter.
          case _ => next

        }
    }

}
