package io.buoyant.router

import com.twitter.finagle.{Filter, Path, Stack, Stackable, ServiceFactory, param}
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.service.StatsFilter
import com.twitter.finagle.stats.{ExceptionStatsHandler, StatsReceiver}
import io.buoyant.router.context.ResponseClassifierCtx

object PerDstPathStatsFilter {
  val role = Stack.Role("PerDstPathStatsFilter")

  def module[Req, Rsp]: Stackable[ServiceFactory[Req, Rsp]] =
    new Stack.Module3[param.Stats, param.ExceptionStatsHandler, StatsFilter.Param, ServiceFactory[Req, Rsp]] {
      val role = PerDstPathStatsFilter.role
      val description = "Report request statistics for each logical destination"

      def make(
        _stats: param.Stats,
        _exceptions: param.ExceptionStatsHandler,
        _unit: StatsFilter.Param,
        next: ServiceFactory[Req, Rsp]
      ): ServiceFactory[Req, Rsp] = _stats match {
        case param.Stats(statsReceiver) if !statsReceiver.isNull =>
          val param.ExceptionStatsHandler(handler) = _exceptions
          val StatsFilter.Param(unit) = _unit

          def mkScopedStatsFilter(path: Path): Filter[Req, Rsp, Req, Rsp] = {
            val name = path.show.stripPrefix("/")
            val sr = statsReceiver.scope("service", name)
            val param.ResponseClassifier(classifier) =
              ResponseClassifierCtx.current.getOrElse(param.ResponseClassifier.param.default)
            new StatsFilter(sr, classifier, handler, unit)
          }

          val filter = new PerDstPathFilter(mkScopedStatsFilter _)
          filter.andThen(next)

        case _ => next
      }
    }

}
