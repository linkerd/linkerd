package io.buoyant.router

import com.twitter.finagle._
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.service.StatsFilter
import com.twitter.finagle.stats.{ExceptionStatsHandler, StatsReceiver}
import com.twitter.util.{Future, Memoize}

class PerDstPathStatsFilter[Req, Rsp](
  statsReceiver: StatsReceiver,
  mkFilter: StatsReceiver => Filter[Req, Rsp, Req, Rsp]
) extends SimpleFilter[Req, Rsp] {

  private[this] val getFilter = Memoize[Path, Filter[Req, Rsp, Req, Rsp]] { path =>
    mkFilter(statsReceiver.scope("dst/path", path.show.stripPrefix("/")))
  }

  def apply(req: Req, service: Service[Req, Rsp]): Future[Rsp] =
    ctx.DstPath.current match {
      case None | Some(Dst.Path(Path.empty, _, _)) =>
        service(req)

      case Some(Dst.Path(path, _, _)) =>
        val filter = getFilter(path)
        filter(req, service)
    }
}

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
          val mkScoped: StatsReceiver => Filter[Req, Rsp, Req, Rsp] =
            sr => new StatsFilter(sr, classifier, handler, unit)

          val filter = new PerDstPathStatsFilter(statsReceiver, mkScoped)
          filter.andThen(next)

        case _ => next
      }
    }

}
