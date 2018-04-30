package io.buoyant.router.http

import com.twitter.finagle._
import com.twitter.finagle.http.{Request, Response}
import com.twitter.util.Future
import io.buoyant.router.ForwardedHeaderLabeler
import io.buoyant.router.ForwardedHeaderLabeler.Enabled
import java.net._
import scala.collection.mutable

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

class LabelingProxy(
  byLabeler: ForwardedHeaderLabeler.By,
  forLabeler: ForwardedHeaderLabeler.For,
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

object AddForwardedHeader {

  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module3[Enabled, ForwardedHeaderLabeler.By, ForwardedHeaderLabeler.For, ServiceFactory[Request, Response]] {
      val role = Stack.Role("AddForwardedHeader")
      val description = "Adds a RFC7239 'Forwarded' header to requests as they are received"
      def make(
        enabled: Enabled,
        byl: ForwardedHeaderLabeler.By,
        forl: ForwardedHeaderLabeler.For,
        next: ServiceFactory[Request, Response]
      ) = enabled match {
        case Enabled(false) => next
        case Enabled(true) => new LabelingProxy(byl, forl, next)
      }
    }
}
