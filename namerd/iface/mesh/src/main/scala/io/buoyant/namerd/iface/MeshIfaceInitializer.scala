package io.buoyant.namerd
package iface

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Namer, Path, Service, Stack}
import com.twitter.finagle.buoyant.H2
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.netty4.ssl.server.Netty4ServerEngineFactory
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.util.Duration
import com.twitter.util.TimeConversions._
import io.buoyant.grpc.runtime.ServerDispatcher
import io.netty.handler.ssl.ApplicationProtocolNames
import java.net.{InetAddress, InetSocketAddress}

import com.twitter.finagle.tracing.NullTracer

class MeshIfaceConfig extends InterfaceConfig {
  @JsonIgnore
  override protected def defaultAddr = MeshIfaceInitializer.defaultAddr

  @JsonIgnore
  override def tlsParams = tls.map(
    _.params(
      Some(Seq(ApplicationProtocolNames.HTTP_2)),
      Netty4ServerEngineFactory()
    )
  ).getOrElse(Stack.Params.empty)

  @JsonIgnore
  override def mk(
    store: DtabStore,
    namers: Map[Path, Namer],
    stats: StatsReceiver
  ): Servable = new Servable {
    def kind = MeshIfaceInitializer.kind
    private[this] val stats1 = stats.scope(kind)

    def serve() = {
      val dispatcher = {
        val codec = mesh.CodecService()
        val interpreter = mesh.InterpreterService(store, namers, stats1)
        val delegator = mesh.DelegatorService(store, namers, stats1)
        val resolver = mesh.ResolverService(namers, stats1)
        ServerDispatcher(codec, interpreter, delegator, resolver)
      }

      H2.server.withTracer(NullTracer).configuredParams(tlsParams).withStatsReceiver(stats1)
        .serve(addr, dispatcher)
    }
  }
}

object MeshIfaceInitializer {
  val kind = "io.l5d.mesh"
  val defaultAddr = new InetSocketAddress(InetAddress.getLoopbackAddress, 4321)
}

class MeshIfaceInitializer extends InterfaceInitializer {
  override val configId = MeshIfaceInitializer.kind
  override val configClass = classOf[MeshIfaceConfig]
}
