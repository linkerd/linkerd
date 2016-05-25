package io.buoyant.router

import com.twitter.finagle.{param => _, _}
import com.twitter.finagle.buoyant.{Dst, DstBindingFactory}
import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.finagle.tracing.Trace
import com.twitter.util.{Future, Time}
import java.util.concurrent.atomic.AtomicInteger

object RoutingFactory {
  val role = Stack.Role("RoutingFactory")
  val description = "Performs per-request name binding"

  case class UnknownDst[Req](request: Req, cause: Throwable)
    extends Exception(s"Unknown destination: $request / ${cause.getMessage}", cause)
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
  label: String,
  statsReceiver: StatsReceiver = NullStatsReceiver
) extends ServiceFactory[Req, Rsp] {
  import RoutingFactory._

  private[this] val pending = new AtomicInteger(0)
  private[this] val pendingGauge = statsReceiver.addGauge("pending") { pending.get.toFloat }

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
  private[this] val service = Future.value(new RoutingService)

  private class RoutingService extends Service[Req, Rsp] {
    def apply(req: Req): Future[Rsp] = {
      if (Trace.isActivelyTracing) {
        // we treat the router label as the rpc name for this span
        Trace.recordRpc(label)
        Trace.recordBinary("router.label", label)
      }
      val recorder = mkIdErrRecorder(req)
      val requester = mkRequester(req)
      getDst(req).rescue(recorder).flatMap(getClient).flatMap(requester)
    }

    private[this] def mkIdErrRecorder(req: Req): PartialFunction[Throwable, Future[Dst]] = {
      case e: Throwable =>
        Annotations.Failure.Identification.record(e)
        Future.exception(UnknownDst(req, e))
    }

    private[this] val getClient: Dst => Future[Service[Req, Rsp]] =
      (dst: Dst) => {
        clientFactory(dst, ClientConnection.nil)
          .onSuccess(markPending)
          .onFailure(Annotations.Failure.ClientAcquisition.record)
      }

    private[this] val markPending: Any => Unit =
      _ => {
        val _ = pending.incrementAndGet()
      }

    private[this] def mkRequester(req: Req): Service[Req, Rsp] => Future[Rsp] =
      (service: Service[Req, Rsp]) => {
        service(req).ensure {
          val _p = pending.decrementAndGet()
          val _c = service.close()
        }
      }

  }
}
