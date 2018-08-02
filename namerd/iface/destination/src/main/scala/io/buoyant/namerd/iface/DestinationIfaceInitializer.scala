package io.buoyant.namerd
package iface

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.buoyant.H2
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.tracing.NullTracer
import com.twitter.finagle.{ListeningServer, Namer, Path}
import com.twitter.util.Activity
import io.buoyant.grpc.runtime.ServerDispatcher
import io.buoyant.namer.ConfiguredNamersInterpreter
import io.buoyant.namerd.iface.destination.DestinationService
import io.linkerd.proxy.destination.Destination
import java.net.{InetAddress, InetSocketAddress}

class DestinationIfaceConfig extends InterpreterInterfaceConfig {
  @JsonIgnore
  override protected def defaultAddr: InetSocketAddress = DestinationIfaceInitializer.defaultAddr

  //  @JsonIgnore
  //  override def mk(
  //    store: DtabStore,
  //    namers: Map[Path, Namer],
  //    stats: StatsReceiver
  //  ): Servable = new Servable {
  //    override def kind: String = DestinationIfaceInitializer.kind
  //
  //    override def serve(): ListeningServer = {
  //      val interpreter = ConfiguredNamersInterpreter(namers.toSeq)
  //      val destination = new DestinationService(interpreter)
  //      val dispatcher = ServerDispatcher(Destination.Server(destination))
  //      H2.server.withTracer(NullTracer).serve(addr, dispatcher)
  //    }
  //  }
  override protected def mk(
    delegate: Ns => NameInterpreter,
    namers: Map[Path, Namer],
    store: DtabStore,
    stats: StatsReceiver
  ): Servable = new Servable {
    override def kind: String = DestinationIfaceInitializer.kind

    override def serve(): ListeningServer = {
      val destination = new DestinationService(delegate("default"))
      val dispatcher = ServerDispatcher(Destination.Server(destination))
      H2.server.withTracer(NullTracer).serve(addr, dispatcher)
    }
  }
}

object DestinationIfaceInitializer {
  val kind = "io.l5d.destination"
  val defaultAddr = new InetSocketAddress(InetAddress.getLoopbackAddress, 4333)
}

class DestinationIfaceInitializer extends InterfaceInitializer {
  override val configId = DestinationIfaceInitializer.kind
  val configClass = classOf[DestinationIfaceConfig]
}
