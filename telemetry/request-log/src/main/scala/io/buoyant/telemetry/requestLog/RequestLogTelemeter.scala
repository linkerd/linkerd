package io.buoyant.telemetry.requestLog

import com.twitter.finagle.stats.NullStatsReceiver
import io.buoyant.admin.Admin.{Handlers, WithHandlers}
import io.buoyant.telemetry.Telemeter

class RequestLogTelemeter(sampleRate: Double, capacity: Int) extends Telemeter with WithHandlers {
  val stats = NullStatsReceiver
  val tracer = new RequestLogTracer(sampleRate, capacity)
  def run() = Telemeter.nopRun

  private[this] val handler = new RequestLogAdminHandler(tracer)

  override def adminHandlers: Handlers = Seq("/requests" -> handler)
}
