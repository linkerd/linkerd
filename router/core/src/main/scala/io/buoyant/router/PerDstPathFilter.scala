package io.buoyant.router

import com.twitter.finagle.{Path, Filter, Service, SimpleFilter}
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.service.StatsFilter
import com.twitter.util.{Future, Memoize}
import io.buoyant.router.context.DstPathCtx

class PerDstPathFilter[Req, Rsp](mk: Path => Filter[Req, Rsp, Req, Rsp])
  extends SimpleFilter[Req, Rsp] {

  private[this] val getFilter = Memoize(mk)

  def apply(req: Req, service: Service[Req, Rsp]): Future[Rsp] =
    DstPathCtx.current match {
      case None | Some(Dst.Path(Path.empty, _, _)) =>
        service(req)

      case Some(Dst.Path(path, _, _)) =>
        val filter = getFilter(path)
        filter(req, service)
    }
}
