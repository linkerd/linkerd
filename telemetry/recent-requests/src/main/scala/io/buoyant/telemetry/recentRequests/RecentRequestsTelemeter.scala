package io.buoyant.telemetry.recentRequests

import com.twitter.finagle.stats.NullStatsReceiver
import io.buoyant.admin.Admin.{Handlers, WithHandlers}
import io.buoyant.telemetry.Telemeter

class RecentRequestsTelemeter(sampleRate: Double, capacity: Int) extends Telemeter with WithHandlers {
  val stats = NullStatsReceiver
  val tracer = new RecentRequetsTracer(sampleRate, capacity)
  def run() = Telemeter.nopRun

  private[this] val handler = new RecentRequestsAdminHandler(tracer)

  override def adminHandlers: Handlers = Seq("/requests" -> handler)
}
