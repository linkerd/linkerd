package io.buoyant.router.h2

import com.twitter.finagle.buoyant.h2.{Request, Response}
import com.twitter.finagle._
import com.twitter.util.Future
import io.buoyant.router.ForwardedHeaderLabeler
import io.buoyant.router.ForwardedHeaderLabeler.Enabled
import scala.collection.mutable

class H2AddForwardedHeader(byLabel: () => String, forLabel: () => String) extends SimpleFilter[Request, Response] {
  def apply(req: Request, svc: Service[Request, Response]): Future[Response] = {
    val forwarded = new mutable.StringBuilder(128)

    forwarded ++= s"by=${byLabel()};for=${forLabel()}"

    val reqHost = req.authority match {
      case "" => ""
      case auth => s";authority=$auth"
    }

    val proto = req.scheme match {
      case "" => ""
      case protocol => s";proto=$protocol"
    }

    forwarded ++= reqHost
    forwarded ++= proto

    /*  RFC7540 8.1.2:
    * A request or response containing uppercase header field names MUST be treated as malformed;
    * Hence the lowercase format of the forwarded header. Netty's H2 implementation enforces this.
    * */
    req.headers.add("forwarded", forwarded.result)
    svc(req)
  }
}

class H2LabelingProxy(
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
    val filter = new H2AddForwardedHeader(byl, forl)
    self.apply(conn).map(filter.andThen(_))
  }
}

object H2AddForwardedHeader {

  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module3[Enabled, ForwardedHeaderLabeler.By, ForwardedHeaderLabeler.For, ServiceFactory[Request, Response]] {
      val role = Stack.Role("H2AddForwardedHeader")
      val description = "Adds a 'Forwarded' header to requests as they are received"
      def make(
        enabled: Enabled,
        byl: ForwardedHeaderLabeler.By,
        forl: ForwardedHeaderLabeler.For,
        next: ServiceFactory[Request, Response]
      ) = enabled match {
        case Enabled(false) => next
        case Enabled(true) => new H2LabelingProxy(byl, forl, next)
      }
    }
}

