package io.buoyant.telemetry.recentRequests

import com.twitter.finagle.stats.NullStatsReceiver
import io.buoyant.admin.Admin.{Handler, NavItem, WithHandlers, WithNavItems}
import io.buoyant.telemetry.Telemeter

class RecentRequestsTelemeter(sampleRate: Double, capacity: Int) extends Telemeter with WithHandlers with WithNavItems {
  val stats = NullStatsReceiver
  val tracer = new RecentRequetsTracer(sampleRate, capacity)
  def run() = Telemeter.nopRun

  private[this] val handler = new RecentRequestsAdminHandler(tracer)

  override def adminHandlers: Seq[Handler] = Seq(Handler("/requests", handler))

  override def navItems: Seq[NavItem] = Seq(NavItem("requests", "requests"))
}
