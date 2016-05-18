package io.buoyant.config.types

import java.net.{InetSocketAddress, InetAddress}

case class HostAndPort(host: Option[String], port: Option[Port]) {

  def toString(defaultHost: InetAddress, defaultPort: Port): String =
    s"${host.getOrElse(defaultHost)}:${port.getOrElse(defaultPort).port}"

  def toString(defaultHost: InetAddress): String = {
    val p = port.getOrElse(throw new IllegalArgumentException("port must be specified")).port
    s"${host.getOrElse(defaultHost)}:$p"
  }

  def toString(defaultPort: Port): String = {
    val h = host.getOrElse(throw new IllegalArgumentException("host must be specified"))
    s"$h:${port.getOrElse(defaultPort).port}"
  }

  override def toString: String = {
    val h = host.getOrElse(throw new IllegalArgumentException("host must be specified"))
    val p = port.getOrElse(throw new IllegalArgumentException("port must be specified")).port
    s"$h:$p"
  }

  def toInetSocketAddress(defaultHost: String, defaultPort: Port): InetSocketAddress =
    new InetSocketAddress(host.getOrElse(defaultHost), port.getOrElse(defaultPort).port)

  def toInetSocketAddress(defaultHost: String): InetSocketAddress = {
    val p = port.getOrElse(throw new IllegalArgumentException("port must be specified"))
    new InetSocketAddress(host.getOrElse(defaultHost), p.port)
  }

  def toInetSocketAddress(defaultPort: Port): InetSocketAddress = {
    val h = host.getOrElse(throw new IllegalArgumentException("host must be specified"))
    new InetSocketAddress(h, port.getOrElse(defaultPort).port)
  }

  def toInetSocketAddress: InetSocketAddress = {
    val h = host.getOrElse(throw new IllegalArgumentException("host must be specified"))
    val p = port.getOrElse(throw new IllegalArgumentException("port must be specified")).port
    new InetSocketAddress(h, p)
  }
}
