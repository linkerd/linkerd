package com.twitter.finagle.buoyant.zipkin

import com.twitter.conversions.DurationOps._
import com.twitter.finagle.client.DefaultPool
import com.twitter.finagle.stats.{ClientStatsReceiver, NullStatsReceiver}
import com.twitter.finagle.thrift.Protocols
import com.twitter.finagle.{Address, Name, Thrift}
import com.twitter.finagle.thrift.scribe.thriftscala.Scribe
import com.twitter.finagle.tracing.{NullTracer, Record, TraceId, Tracer, TracelessFilter}
import com.twitter.finagle.util.DefaultTimer
import com.twitter.finagle.zipkin.thrift.{ScribeRawZipkinTracer, ZipkinTracer => TZipkinTracer}

class ZipkinTracer(
  host: String,
  port: Int,
  sampleRate: Float
) extends Tracer {

  val underlying: Tracer = {
    // Cribbed heavily from com.twitter.finagle.zipkin.thrift.RawZipkinTracer
    val transport = Thrift.client
      .withStatsReceiver(ClientStatsReceiver)
      .withSessionPool.maxSize(5)
      .configured(DefaultPool.Param.param.default.copy(maxWaiters = 250))
      // reduce timeouts because trace requests should be fast
      .withRequestTimeout(100.millis)
      // disable failure accrual so that we don't evict nodes when connections
      // are saturated
      .withSessionQualifier.noFailureAccrual
      // disable fail fast since we often be sending to a load balancer
      .withSessionQualifier.noFailFast
      .withTracer(NullTracer)
      .newService(Name.bound(Address(host, port)), "zipkin-tracer")

    val client: Scribe.MethodPerEndpoint = new Scribe.FinagledClient(
      new TracelessFilter andThen transport,
      Protocols.binaryFactory()
    )

    val rawTracer = ScribeRawZipkinTracer(
      client,
      "zipkin",
      NullStatsReceiver,
      DefaultTimer
    )
    new TZipkinTracer(
      rawTracer,
      sampleRate
    )
  }

  def sampleTrace(t: TraceId) = underlying.sampleTrace(t)
  def record(r: Record) = underlying.record(r)
}
