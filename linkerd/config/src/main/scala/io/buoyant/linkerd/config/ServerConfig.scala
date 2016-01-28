package io.buoyant.linkerd.config

import java.net.{InetAddress, InetSocketAddress}

import com.google.common.net.InetAddresses

trait ServerConfig {
  def ip: Option[String]
  def port: Option[Int]


  // TODO: unify this code with what's in Server
  private[this] val loopbackIp = InetAddress.getLoopbackAddress
  private[this] val anyIp = InetAddress.getByAddress(Array(0, 0, 0, 0))
  def addr: InetSocketAddress = new InetSocketAddress(
    ip map InetAddresses.forString getOrElse loopbackIp,
    port getOrElse 0)

  def withDefaults(router: RouterConfig): Defaults = new Defaults(this, router)

  def validate(others: Seq[ServerConfig]): Seq[ConfigError] = {
    // TODO: unify this with code in Server.scala
    def conflicts(other: ServerConfig) = {
      val addr0 = other.addr
      val addr1 = this.addr
      val conflict = (addr1.getPort != 0) && (addr0.getPort == addr1.getPort) && {
        val (a0, a1) = (addr0.getAddress, addr1.getAddress)
        a0.isAnyLocalAddress || a1.isAnyLocalAddress || a0 == a1
      }
      if (conflict) Some(ConflictingPorts(addr0, addr1)) else None
    }
    others flatMap conflicts
  }

  class Defaults(base: ServerConfig, router: RouterConfig) extends ServerConfig {
    def ip = base.ip
    def port = base.port
  }
}


case class BaseServerConfig(ip: Option[String] = None, port: Option[Int] = None) extends ServerConfig
