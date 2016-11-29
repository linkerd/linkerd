package io.buoyant.namerd
package iface

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Path, Namer, Service, Stack}
import com.twitter.finagle.buoyant.{H2, h2}
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.util.Duration
import com.twitter.util.TimeConversions._
import io.buoyant.grpc.runtime.ServerDispatcher
import java.net.InetSocketAddress

class GrpcServerConfig extends InterfaceConfig {
  @JsonIgnore
  override protected def defaultAddr = GrpcServerConfig.defaultAddr

  @JsonIgnore
  override def mk(
    store: DtabStore,
    namers: Map[Path, Namer],
    stats: StatsReceiver
  ): Servable = new Servable {
    def kind = GrpcServerConfig.kind
    def serve() = {
      val interpreter = grpc.InterpreterServer(store, namers, stats)
      H2.serve(addr, ServerDispatcher(interpreter))
    }
  }
}

object GrpcServerConfig {
  val kind = "io.l5d.namerd.grpc"
  val defaultAddr = new InetSocketAddress(4321)
}

class GrpcServerInitializer extends InterfaceInitializer {
  override val configId = GrpcServerConfig.kind
  override val configClass = classOf[GrpcServerConfig]
}
