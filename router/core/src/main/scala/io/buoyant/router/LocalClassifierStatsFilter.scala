package io.buoyant.router

import com.twitter.finagle.{Filter, Path, Stack, Stackable, ServiceFactory, param}
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.service.StatsFilter
import com.twitter.finagle.stats.{ExceptionStatsHandler, StatsReceiver}
import io.buoyant.router.context.ResponseClassifierCtx

object LocalClassifierStatsFilter {
  val role = Stack.Role("StatsFilter")

  def module[Req, Rsp]: Stackable[ServiceFactory[Req, Rsp]] =
    new Stack.Module3[param.Stats, param.ExceptionStatsHandler, StatsFilter.Param, ServiceFactory[Req, Rsp]] {
      val role = LocalClassifierStatsFilter.role
      val description = "Report request statistics using local response classifier"

      def make(
        _stats: param.Stats,
        _exceptions: param.ExceptionStatsHandler,
        _unit: StatsFilter.Param,
        next: ServiceFactory[Req, Rsp]
      ): ServiceFactory[Req, Rsp] = _stats match {
        case param.Stats(statsReceiver) if !statsReceiver.isNull =>

          val param.ExceptionStatsHandler(handler) = _exceptions
          val StatsFilter.Param(unit) = _unit

          // We memoize on the dst path.  The assumes that the response classifier for a dst path
          // never changes.
          def mkClassifiedStatsFilter(path: Path): Filter[Req, Rsp, Req, Rsp] = {
            val param.ResponseClassifier(classifier) =
              ResponseClassifierCtx.current.getOrElse(param.ResponseClassifier.param.default)
            new StatsFilter(statsReceiver, classifier, handler, unit)
          }

          val filter = new PerDstPathFilter(mkClassifiedStatsFilter _)
          filter.andThen(next)

        case _ => next
      }
    }

}
