package io.buoyant.router.http

import com.twitter.finagle._
import com.twitter.finagle.http.{Request, Response}
import com.twitter.util.Future
import java.net.{Inet4Address, Inet6Address, InetSocketAddress, SocketAddress, URI, URISyntaxException}
import scala.collection.mutable
import scala.util.Random

/**
 * Appends the [Forwarded](https://tools.ietf.org/html/rfc7239) header to the request.
 *
 * Possible future additions:
 * * parse incoming X-Forwarded-* from legacy proxy services and convert to Forwarded
 */
class AddForwardedHeader(byLabel: () => String, forLabel: () => String) extends SimpleFilter[Request, Response] {

  def apply(req: Request, svc: Service[Request, Response]): Future[Response] = {
    val forwarded = new mutable.StringBuilder(128)

    forwarded ++= s"by=${byLabel()};for=${forLabel()}"

    val host = req.host match {
      case None =>
      case Some(host) =>
        forwarded ++= s";host=$host"
    }

    try {
      val uri = new URI(req.uri)
      uri.getScheme match {
        case null => // no proto value
        case proto =>
          forwarded ++= s";proto=$proto"
      }
    } catch {
      case e: URISyntaxException => // no proto value
    }

    req.headerMap.add("Forwarded", forwarded.result)
    svc(req)
  }
}

object AddForwardedHeader {

  case class Enabled(enabled: Boolean)
  implicit object Param extends Stack.Param[Enabled] {
    // The RFC indicates that this feature should be disabled by default.
    val default = Enabled(false)
  }

  type Labeler = SocketAddress => () => String
  object Labeler {
    private[this] def Const(label: String) = () => label
    private[this] val Unknown = Const("unknown")

    /**
     * Formats addresses like:
     *   192.0.2.43
     *   "[2001:db8:cafe::17]"
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
     *   "192.0.2.43:8080"
     *   "[2001:db8:cafe::17]:8080"
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
     *    Unless static assignment of identifiers is necessary for the
     *    server's use of the identifiers, obfuscated identifiers
     *    SHOULD be randomly generated for each request.
     *
     *    Example:
     *        Forwarded: for=_qb72ZK
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

  class LabelingProxy(
    byLabeler: Labeler.By,
    forLabeler: Labeler.For,
    underlying: ServiceFactory[Request, Response]
  ) extends ServiceFactoryProxy(underlying) {

    override def apply(conn: ClientConnection): Future[Service[Request, Response]] = {
      // The `by` and `for` labelers are computed once per
      // connection. This means that a randomized labeler, for
      // instance, may reuse labels throughout a client's lifetime.
      val byl = byLabeler(conn.localAddress)
      val forl = forLabeler(conn.remoteAddress)
      val filter = new AddForwardedHeader(byl, forl)
      self.apply(conn).map(filter.andThen(_))
    }
  }

  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module3[Enabled, Labeler.By, Labeler.For, ServiceFactory[Request, Response]] {
      val role = Stack.Role("AddForwardedHeader")
      val description = "Adds a RFC7239 'Forwarded' header to requests as they are received"
      def make(
        enabled: Enabled,
        byl: Labeler.By,
        forl: Labeler.For,
        next: ServiceFactory[Request, Response]
      ) = enabled match {
        case Enabled(false) => next
        case Enabled(true) => new LabelingProxy(byl, forl, next)
      }
    }
}
