package io.buoyant.linkerd.config

import java.net.{InetAddress, InetSocketAddress}

import cats.data.{NonEmptyList, OneAnd, ValidatedNel}
import cats.data.Validated._
import com.google.common.net.InetAddresses

trait ServerConfig {
  def ip: Option[String]
  def port: Option[Int]

  // TODO: unify this code with what's in Server
  private[this] val loopbackIp = InetAddress.getLoopbackAddress
  private[this] val anyIp = InetAddress.getByAddress(Array(0, 0, 0, 0))
  def addr: InetSocketAddress = new InetSocketAddress(
    ip map InetAddresses.forString getOrElse loopbackIp,
    port getOrElse 0
  )

  def withDefaults(router: RouterConfig.Defaults): ServerConfig.Defaults =
    new ServerConfig.Defaults(this, router)
}

object ServerConfig {
  class Defaults(base: ServerConfig, router: RouterConfig.Defaults) {
    def ip = base.ip
    def port = base.port
    def addr = base.addr

    val MinValue = 1
    val MaxValue = math.pow(2, 16) - 1

    def validPort(port: Int): Boolean = MinValue <= port && port <= MaxValue

    def validated(others: Seq[Defaults]): ValidatedNel[ConfigError, Validated] = {
      // TODO: unify this with code in Server.scala
      def conflicts(other: Defaults) = {
        val addr0 = other.addr
        val addr1 = this.addr
        val conflict = (addr1.getPort != 0) && (addr0.getPort == addr1.getPort) && {
          val (a0, a1) = (addr0.getAddress, addr1.getAddress)
          a0.isAnyLocalAddress || a1.isAnyLocalAddress || a0 == a1
        }
        if (conflict) Some(ConflictingPorts(addr0, addr1)) else None
      }

      port match {
        case Some(p) if !validPort(p) =>
          invalidNel(InvalidPort(p))
        case _ =>
          val allConflicts: Seq[ConflictingPorts] = others flatMap conflicts
          allConflicts match {
            case c :: cs => invalid(NonEmptyList(c, cs))
            case _ => valid(new Validated(this))
          }
      }
    }
  }

  class Validated(defaults: Defaults) {
    def addr = defaults.addr
  }

  def validateServers(
    servers: Seq[ServerConfig],
    router: RouterConfig.Defaults,
    previousServers: Seq[ServerConfig.Defaults]
  ): ValidatedNel[ConfigError, Seq[ServerConfig.Validated]] = {
    import cats.std.list._
    import cats.syntax.traverse._
    servers.map(_.withDefaults(router).validated(previousServers)).toList.sequenceU
  }
}

case class BaseServerConfig(ip: Option[String] = None, port: Option[Int] = None) extends ServerConfig
