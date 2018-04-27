package io.buoyant.router.http

import java.net.{Inet4Address, Inet6Address, InetSocketAddress, SocketAddress}
import com.twitter.finagle._
import scala.util.Random

object ForwardedHeaderLabeler {
  case class Enabled(enabled: Boolean)
  implicit object Param extends Stack.Param[Enabled] {
    // The RFC indicates that this feature should be disabled by default.
    val default = Enabled(false)
  }

  type Labeler = SocketAddress => () => String

  private[this] def Const(label: String) = () => label
  private[this] val Unknown = Const("unknown")

  /**
   * Formats addresses like:
   *   192.0.2.43
   * "[2001:db8:cafe::17]"
   */
  object ClearIp extends Labeler {
    def apply(sa: SocketAddress): () => String = sa match {
      case isa: InetSocketAddress =>
        isa.getAddress match {
          case ip if ip.isAnyLocalAddress => Unknown
          case ip4: Inet4Address => Const(ip4.getHostAddress)
          case ip6: Inet6Address => Const(s""""[${ip6.getHostAddress}]"""")
          case _ => Unknown
        }
      case _ => Unknown
    }
  }

  /**
   * Formats addresses like:
   * "192.0.2.43:8080"
   * "[2001:db8:cafe::17]:8080"
   */
  object ClearIpPort extends Labeler {
    def apply(sa: SocketAddress): () => String = sa match {
      case isa: InetSocketAddress =>
        val port = isa.getPort
        isa.getAddress match {
          case ip if ip.isAnyLocalAddress => Unknown
          case ip4: Inet4Address => Const(s""""${ip4.getHostAddress}:${port}"""")
          case ip6: Inet6Address => Const(s""""[${ip6.getHostAddress}]:${port}"""")
          case _ => Unknown
        }
      case _ => Unknown
    }
  }

  /**
   * Generates randomized, obfuscated strings to mask IP addresses
   * in accordance with RFC7239 §6.3:
   *
   * Unless static assignment of identifiers is necessary for the
   * server's use of the identifiers, obfuscated identifiers
   * SHOULD be randomly generated for each request.
   *
   * Example:
   * Forwarded: for=_qb72ZK
   */
  object ObfuscatedRandom {

    case class PerRequest(length: Int = 6, rng: Random = Random) extends Labeler {
      def apply(sa: SocketAddress): () => String = sa match {
        case isa: InetSocketAddress =>
          val inet = isa.getAddress
          if (inet.isAnyLocalAddress) Unknown
          else () => s"_${rng.alphanumeric.take(length).mkString}"

        case _ => Unknown
      }
    }

    case class PerConnection(length: Int = 6, rng: Random = Random) extends Labeler {
      def apply(sa: SocketAddress): () => String = sa match {
        case isa: InetSocketAddress =>
          val inet = isa.getAddress
          if (inet.isAnyLocalAddress) Unknown
          else Const(s"_${rng.alphanumeric.take(length).mkString}")
        case _ => Unknown
      }
    }

  }

  case class ObfuscatedStatic(label: String) extends Labeler {
    private[this] val cached: () => String = Const {
      if (label startsWith "_") label
      else s"_${label}"
    }

    def apply(sa: SocketAddress): () => String = cached
  }

  case class By(labeler: Labeler) extends Labeler {
    def apply(sa: SocketAddress) = labeler(sa)
  }

  implicit object By extends Stack.Param[By] {
    val default = By(ObfuscatedRandom.PerRequest())
  }

  case class For(labeler: Labeler) extends Labeler {
    def apply(sa: SocketAddress) = labeler(sa)
  }

  implicit object For extends Stack.Param[For] {
    val default = For(ObfuscatedRandom.PerRequest())
  }

}
