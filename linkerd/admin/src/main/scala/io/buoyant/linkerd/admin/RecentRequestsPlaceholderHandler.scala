package io.buoyant.linkerd.admin

import com.twitter.finagle.http.{Request, Response}
import com.twitter.util.Future
import io.buoyant.admin.Admin.Handler

class RecentRequestsPlaceholderHandler extends Handler {
  override def apply(request: Request): Future[Response] = {
    val html = AdminHandler.html("""
      |<div class="container main">
      |<h1 class="title">Recent Requests</h1>
      |<div class="content">
      |<p>In order to see a log of recent requests, you must add a
      |recentRequests telemeter to your linkerd config.  e.g. </p>
      |<pre><code class="language-yaml">telemetry:
      |- kind: io.l5d.commonMetrics
      |- kind: io.l5d.recentRequests
      |  sampleRate: 1.0
      |</code></pre>
      |</div>
      |</div>
    """.stripMargin)
    AdminHandler.mkResponse(html)
  }
}
