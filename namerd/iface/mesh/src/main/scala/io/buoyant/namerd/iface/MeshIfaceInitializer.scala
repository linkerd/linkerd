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

class MeshIfaceConfig extends InterfaceConfig {
  @JsonIgnore
  override protected def defaultAddr = MeshIfaceInitializer.defaultAddr

  @JsonIgnore
  override def mk(
    store: DtabStore,
    namers: Map[Path, Namer],
    stats: StatsReceiver
  ): Servable = new Servable {
    def kind = MeshIfaceInitializer.kind
    def serve() = {
      val dispatcher = {
        val codec = mesh.CodecService()
        val interpreter = mesh.InterpreterService(store, namers, stats)
        val delegator = mesh.DelegatorService(store, namers, stats)
        val resolver = mesh.ResolverService(namers, stats)
        ServerDispatcher(codec, interpreter, delegator, resolver)
      }
      H2.serve(addr, dispatcher)
    }
  }
}

object MeshIfaceInitializer {
  val kind = "io.l5d.mesh"
  val defaultAddr = new InetSocketAddress(4321)
}

class MeshIfaceInitializer extends InterfaceInitializer {
  override val configId = MeshIfaceInitializer.kind
  override val configClass = classOf[MeshIfaceConfig]
}
