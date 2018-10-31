package io.buoyant.namerd
package iface

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.buoyant.H2
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.tracing.NullTracer
import com.twitter.finagle.{ListeningServer, Namer, Path}
import com.twitter.logging.Logger
import io.buoyant.grpc.runtime.ServerDispatcher
import io.buoyant.namerd.iface.destination.DestinationService
import io.linkerd.proxy.destination.Destination
import java.net.{InetAddress, InetSocketAddress}

class DestinationIfaceConfig(
  namespace: Option[String],
  prefix: Option[String]
) extends InterpreterInterfaceConfig {

  @JsonIgnore
  private[this] val log = Logger.get(getClass.getName)

  @JsonIgnore
  override protected def defaultAddr: InetSocketAddress = DestinationIfaceInitializer.defaultAddr

  @JsonIgnore
  override protected def mk(
    delegate: Ns => NameInterpreter,
    namers: Map[Path, Namer],
    store: DtabStore,
    stats: StatsReceiver
  ): Servable = new Servable {
    override def kind: String = DestinationIfaceInitializer.kind

    override def serve(): ListeningServer = {
      val pfx = prefix.getOrElse("/svc").drop(1)
      val ns = namespace.getOrElse("default")
      log.info(s"DestinationIfaceInitializer using dtab in $ns with prefix $pfx")
      val destination = new DestinationService(pfx, delegate(ns))
      val dispatcher = ServerDispatcher(Destination.Server(destination))
      H2.server
        .configuredParams(socketOptParams)
        .withTracer(NullTracer).serve(addr, dispatcher)
    }
  }
}

object DestinationIfaceInitializer {
  val kind = "io.l5d.destination"
  val defaultAddr = new InetSocketAddress(InetAddress.getLoopbackAddress, 8086)
}

class DestinationIfaceInitializer extends InterfaceInitializer {
  override val configId = DestinationIfaceInitializer.kind
  val configClass = classOf[DestinationIfaceConfig]
}
