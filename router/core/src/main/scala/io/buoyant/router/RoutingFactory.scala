package io.buoyant.router

import com.twitter.finagle.{param => _, _}
import com.twitter.finagle.buoyant.{Dst, DstBindingFactory}
import com.twitter.finagle.tracing.Trace
import com.twitter.util.{Future, Time}

object RoutingFactory {
  val role = Stack.Role("RoutingFactory")
  val description = "Performs per-request name binding"

  case class UnknownDst[Req](request: Req, cause: Throwable)
    extends Exception(s"Unknown destination: ${cause.getMessage}", cause)
    with NoStacktrace

  /**
   * An Identifier determines a [[com.twitter.finagle.buoyant.Dst
   * destination]] for `Req`-typed requests.
   */
  type Identifier[Req] = Req => Future[Dst]

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

  object Annotations {
    sealed abstract class Failure(val name: String) {
      override def toString = name
    }
    object Failure {
      val key = "io.buoyant.router.Failure"
      object Identification extends Failure("Identification")
      object ClientAcquisition extends Failure("ClientAcquisition")
      object Service extends Failure("Service")
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
  clientFactory: DstBindingFactory[Req, Rsp]
) extends ServiceFactory[Req, Rsp] {
  import RoutingFactory._

  override def close(deadline: Time): Future[Unit] = clientFactory.close(deadline)
  override def status: Status = clientFactory.status

  def apply(conn: ClientConnection): Future[Service[Req, Rsp]] =
    Future.value(new RoutingService(conn))

  // TODO move trace recording into a separate stack module?
  private class RoutingService(conn: ClientConnection) extends Service[Req, Rsp] {
    override def close(d: Time) = conn.close(d)

    def apply(req: Req): Future[Rsp] =
      for {
        dst <- getDst(req).rescue {
          case e: Throwable =>
            record(Annotations.Failure.Identification)
            Future.exception(UnknownDst(req, e))
        }

        service <- clientFactory(dst, conn)
          .onFailure(_ => record(Annotations.Failure.ClientAcquisition))

        rsp <- service(req)
          .onFailure(_ => record(Annotations.Failure.Service))
          .ensure(service.close())
      } yield rsp

    private[this] def record(ann: Annotations.Failure): Unit =
      Trace.recordBinary(Annotations.Failure.key, ann)

  }
}
