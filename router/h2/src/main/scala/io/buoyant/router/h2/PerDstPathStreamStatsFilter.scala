package io.buoyant.router.h2

import com.twitter.finagle.param.{ExceptionStatsHandler, Stats}
import com.twitter.finagle.{ServiceFactory, Stack, Stackable, param}
import com.twitter.finagle.service.StatsFilter
import io.buoyant.router.PerDstPathStatsFilter

/**
  * Like [[io.buoyant.router.PerDstPathStatsFilter]],
  * but specialized for H2 streams.
  */
object PerDstPathStreamStatsFilter {

  def module[Req, Rsp]: Stackable[ServiceFactory[Req, Rsp]] =
    new Stack.Module3[param.Stats, param.ExceptionStatsHandler, StreamStatsFilter.Param, ServiceFactory[Req, Rsp]] {
      val role = PerDstPathStatsFilter.role
      val description = "Report request statistics for each logical destination"

      override def make(
        p1: Stats,
        p2: ExceptionStatsHandler,
        p3: StreamStatsFilter.Param,
        next: ServiceFactory[Req, Rsp]): ServiceFactory[Req, Rsp] = ???
    }
}
