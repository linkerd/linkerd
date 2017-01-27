package io.buoyant.router

import com.twitter.finagle.{param => _, _}
import com.twitter.finagle.buoyant.{Dst, DstBindingFactory}
import com.twitter.finagle.tracing.Trace
import com.twitter.util.{Throw, Return, Future, Time}
import scala.util.control.NoStackTrace

object RoutingFactory {
  val role = Stack.Role("RoutingFactory")
  val description = "Performs per-request name binding"

  /**
   * An Identifier determines a [[com.twitter.finagle.buoyant.Dst
   * destination]] for `Req`-typed requests.
   */
  type Identifier[Req] = Req => Future[RequestIdentification[Req]]

  object Identifier {

    def compose[Req](hd: Identifier[Req], tl: Identifier[Req]*): Identifier[Req] =
      (req: Req) => fold(req, hd, tl)

    /**
     * Apply each identifier in order until the request is identified.
     *
     * Identifier list must not be empty.
     */
    def compose[Req](all: Seq[Identifier[Req]]): Identifier[Req] = all match {
      case Nil => throw new IllegalArgumentException("empty identifier list")
      case Seq(identifier) => identifier
      case Seq(hd, tl@_*) => (req: Req) => fold(req, hd, tl)
    }

    /**
     * Apply each identifier to the request in order until the request
     * is identified or identifiers are exhausted.
     */
    private[this] def fold[Req](
      req: Req,
      hd: Identifier[Req],
      tl: Seq[Identifier[Req]]
    ): Future[RequestIdentification[Req]] =
      tl match {
        case Nil => hd(req)
        case Seq(nextHd, nextTl@_*) =>
          hd(req).flatMap {
            case id: IdentifiedRequest[Req] => Future.value(id)
            case _: UnidentifiedRequest[Req] => fold(req, nextHd, nextTl)
          }
      }

  }

  /** The result of attempting to identify a request. */
  sealed trait RequestIdentification[Req]

  /**
   * This indicates that a destination was successfully assigned.  The attached
   * request should be sent to the destination.
   * @param dst The destination of the request.
   * @param request The request to send to the destination.  This allows
   *                identifiers to effectively mutate requests that they
   *                identify.
   */
  class IdentifiedRequest[Req](val dst: Dst.Path, val request: Req) extends RequestIdentification[Req]

  object IdentifiedRequest {
    def unapply[Req](identified: IdentifiedRequest[Req]): Option[(Dst.Path, Req)] =
      Some((identified.dst, identified.request))
  }

  /**
   * This indicates that the identifier could not assign a destination to the
   * request.
   */
  class UnidentifiedRequest[Req](val reason: String) extends RequestIdentification[Req]

  /** Indicates that no destination could be found for a request. */
  case class UnknownDst[Req](request: Req, reason: String)
    extends Exception(s"Unknown destination: $request / $reason")
    with NoStackTrace

  /**
   * A prefix to be assigned to [[com.twitter.finagle.buoyant.Dst
   * destinations]] (i.e. by an [[Identifier]]).
   */
  case class DstPrefix(path: Path)
  implicit object DstPrefix extends Stack.Param[DstPrefix] {
    val default = DstPrefix(Path.empty)
  }

  /**
   * The base `Dtab` to be used when evaluating a
   * [[com.twitter.finagle.buoyant.Dst destination]].
   */
  case class BaseDtab(dtab: () => Dtab)
  implicit object BaseDtab extends Stack.Param[BaseDtab] {
    val default = BaseDtab(() => Dtab.base)
  }

  private[buoyant] object Annotations {
    sealed abstract class Failure(val name: String) {
      override def toString = name
      val record: Any => Unit = (_: Any) => Trace.recordBinary(Failure.key, name)
    }
    object Failure {
      val key = "io.buoyant.router.Failure"
      case object Identification extends Failure("Identification")
      case object ClientAcquisition extends Failure("ClientAcquisition")
      case object Service extends Failure("Service")
      def unapply(f: Failure): Option[String] = Some(f.name)
    }
  }
}

/**
 * A RoutingFactory is a ServiceFactory that, for each request,
 * determines a routable name for the request, resolves it to a
 * client, and dispatches the request to a downstream service.
 *
 * `getDst` is responsible for determining the name (Path) of each
 * request.
 *
 * A DstBindingFactory is used to obtain a client that serves each
 * destination.
 */
class RoutingFactory[Req, Rsp](
  getDst: RoutingFactory.Identifier[Req],
  clientFactory: DstBindingFactory[Req, Rsp],
  label: String
) extends ServiceFactory[Req, Rsp] {
  import RoutingFactory._

  override def close(deadline: Time): Future[Unit] = clientFactory.close(deadline)
  override def status: Status = clientFactory.status

  def apply(conn: ClientConnection): Future[Service[Req, Rsp]] = service

  /**
   * The router doesn't actually need a reference to the client
   * connection, so we can use a nil client connection (as would be
   * the case when manually constructing a Finagle Client
   * ServiceFactory). This has a notable impact on performance,
   * especially in the face of server connection churn.
   */
  private val service = Future.value(new RoutingService(ClientConnection.nil))

  // TODO move trace recording into a separate stack module?
  private class RoutingService(conn: ClientConnection) extends Service[Req, Rsp] {
    override def close(d: Time) = conn.close(d)

    def apply(req0: Req): Future[Rsp] = {
      if (Trace.isActivelyTracing) {
        // we treat the router label as the rpc name for this span
        Trace.recordRpc(label)
        Trace.recordBinary("router.label", label)
      }

      for {
        IdentifiedRequest(dst, req1) <- getDst(req0).transform {
          case Return(identified: IdentifiedRequest[Req]) =>
            Future.value(identified)
          case Return(unidentified: UnidentifiedRequest[Req]) =>
            Annotations.Failure.Identification.record(unidentified.reason)
            Future.exception(UnknownDst(req0, unidentified.reason))
          case Throw(e) =>
            Annotations.Failure.Identification.record(e.getMessage)
            Future.exception(UnknownDst(req0, e.getMessage))
        }

        // Client acquisition failures are recorded within the
        // clientFactory's path stack.
        service <- clientFactory(dst, conn).onFailure(Annotations.Failure.ClientAcquisition.record)

        // Service failures are recorded within the clientFactory's
        // path stack, too.
        rsp <- service(req1).ensure {
          val _ = service.close()
        }
      } yield rsp
    }
  }
}
