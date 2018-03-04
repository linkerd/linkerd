package io.buoyant.linkerd

import java.net.{InetAddress, InetSocketAddress, NetworkInterface}

import scala.collection.JavaConverters._

object InetSocketAddresses {

  private def networkInterfaces: Seq[InetAddress] =
    NetworkInterface.getNetworkInterfaces.asScala.toSeq
      .filter(_.isUp)
      .flatMap(_.getInetAddresses.asScala.toSeq)
      .filterNot(_.isLoopbackAddress)

  def listeningOn(port: Int): Seq[InetSocketAddress] =
    networkInterfaces
      .map(address => new InetSocketAddress(address, port))
}
