package io.buoyant.admin

import com.twitter.finagle.http.{Method, Request, Response}
import com.twitter.server.handler.MetricQueryHandler
import com.twitter.util.Future

class MetricsQueryHandler extends MetricQueryHandler {
  override def apply(req: Request): Future[Response] = {
    if (req.method == Method.Post) {
      req.uri = s"/admin/metrics?${req.contentString}"
    }
    super.apply(req)
  }
}
