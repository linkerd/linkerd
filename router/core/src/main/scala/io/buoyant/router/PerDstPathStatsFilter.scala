package io.buoyant.router

import com.twitter.finagle.{Filter, Path, Stack, Stackable, ServiceFactory, param}
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.service.StatsFilter
import com.twitter.finagle.stats.{ExceptionStatsHandler, StatsReceiver}

object PerDstPathStatsFilter {
  val role = Stack.Role("PerDstPathStatsFilter")

  def module[Req, Rsp]: Stackable[ServiceFactory[Req, Rsp]] =
    new Stack.Module4[param.Stats, param.ExceptionStatsHandler, param.ResponseClassifier, StatsFilter.Param, ServiceFactory[Req, Rsp]] {
      val role = PerDstPathStatsFilter.role
      val description = "Report request statistics for each logical destination"

      def make(
        _stats: param.Stats,
        _exceptions: param.ExceptionStatsHandler,
        _classifier: param.ResponseClassifier,
        _unit: StatsFilter.Param,
        next: ServiceFactory[Req, Rsp]
      ): ServiceFactory[Req, Rsp] = _stats match {
        case param.Stats(statsReceiver) if !statsReceiver.isNull =>
          val param.ResponseClassifier(classifier) = _classifier
          val param.ExceptionStatsHandler(handler) = _exceptions
          val StatsFilter.Param(unit) = _unit

          def mkScopedStatsFilter(path: Path): Filter[Req, Rsp, Req, Rsp] = {
            val name = path.show.stripPrefix("/")
            val sr = statsReceiver.scope("service", name)
            new StatsFilter(sr, classifier, handler, unit)
          }

          val filter = new PerDstPathFilter(mkScopedStatsFilter _)
          filter.andThen(next)

        case _ => next
      }
    }

}
