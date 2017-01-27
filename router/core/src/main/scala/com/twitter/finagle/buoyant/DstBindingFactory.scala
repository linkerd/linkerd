package com.twitter.finagle.buoyant

import com.twitter.finagle._
import com.twitter.finagle.factory.{NameTreeFactory, ServiceFactoryCache}
import com.twitter.finagle.naming._
import com.twitter.finagle.stats.{DefaultStatsReceiver, StatsReceiver}
import com.twitter.finagle.util.DefaultTimer
import com.twitter.logging.Logger
import com.twitter.util._
import java.util.concurrent.atomic.AtomicReference

trait DstBindingFactory[-Req, +Rsp] extends Closable {
  final def apply(dst: Dst.Path): Future[Service[Req, Rsp]] =
    apply(dst, ClientConnection.nil)

  def apply(dst: Dst.Path, conn: ClientConnection): Future[Service[Req, Rsp]]

  def status: Status
}

object DstBindingFactory {

  private[buoyant] class RefCount {
    // If non-None, refcount >= 0, indicating the number of active
    // references.  When None, the reference count may not change.
    private[this] var refcount: Option[Long] = Some(0)
    private[this] def update(f: Long => Long): Option[Long] = synchronized {
      refcount = refcount.map(f).filter(_ > 0)
      refcount
    }

    def get: Option[Long] = synchronized(refcount)
    def incr(): Option[Long] = update(_ + 1)
    def decr(): Option[Long] = update(_ - 1)
  }

  /**
   * Ensures that a DstBindignFactory is only closed when all users of
   * the factory have closed it.
   *
   * Note that acquire() / close() are only expected to be called in
   * the context of process configuration and not, for example, in the
   * request serving path.
   */
  class RefCounted[Req, Rsp](underlying: DstBindingFactory[Req, Rsp]) {

    private[this] val refCount = new RefCount
    def references: Long = refCount.get.getOrElse(0)

    private[this] val release = new Closable {
      def close(deadline: Time) = refCount.decr() match {
        case None =>
          underlying.close(deadline)
        case Some(c) if (c <= 0) =>
          Future.exception(new IllegalStateException(s"Closing factory with $c references"))
        case _ =>
          Future.Unit
      }
    }

    def acquire(): DstBindingFactory[Req, Rsp] = refCount.incr() match {
      case None =>
        throw new IllegalStateException("Acquiring factory after it was closed")
      case Some(c) if (c <= 0) =>
        throw new IllegalStateException(s"Acquiring factory with $c references")
      case _ =>
        // Ensure that we can only decrement once for each acquisition
        // by proxying close() on the underlying RpcClientFactory.
        val closable = Closable.ref(new AtomicReference(release))
        new DstBindingFactory[Req, Rsp] {
          def apply(dst: Dst.Path, conn: ClientConnection) = underlying(dst, conn)
          def status = underlying.status
          def close(deadline: Time) = closable.close(deadline)
        }
    }
  }

  def refcount[Req, Rsp](underlying: DstBindingFactory[Req, Rsp]): RefCounted[Req, Rsp] =
    new RefCounted(underlying)

  /**
   * A convenience type for a function that modifies (e.g. filters) a
   * ServiceFactory using a T-typed value.
   */
  type Mk[T, Req, Rsp] = (T, ServiceFactory[Req, Rsp]) => ServiceFactory[Req, Rsp]
  object Mk {
    def identity[T, Req, Rsp]: Mk[T, Req, Rsp] =
      (_: T, f: ServiceFactory[Req, Rsp]) => f
  }

  case class Namer(interpreter: NameInterpreter) {
    /** For Java compatibility */
    def mk(): (Namer, Stack.Param[Namer]) = (this, Namer)
  }

  implicit object Namer extends Stack.Param[Namer] {
    val default = Namer(DefaultInterpreter)
  }

  /** The capacities for each layer of dst caching. */
  case class Capacity(paths: Int, trees: Int, bounds: Int, clients: Int) {
    /** For Java compatibility */
    def mk(): (Capacity, Stack.Param[Capacity]) = (this, Capacity)
  }

  implicit object Capacity extends Stack.Param[Capacity] {
    val default = Capacity(100, 100, 100, 10)
  }

  case class BindingTimeout(timeout: Duration)
  implicit object BindingTimeout extends Stack.Param[BindingTimeout] {
    val default = BindingTimeout(Duration.Top)
  }

  /**
   * Binds a Dst to a ServiceFactory.
   *
   * Here, we're basically replicating the logic from Finagle's
   * BindingFactory. This is done so we bind a destination before
   * creating a client so that multiple requests to a single bound
   * destination may share connection pools etc.
   *
   * The logic has been changed to account for the way residuals play
   * into naming.  We use the helper classes Bound and BoundTree
   * instead of Name.Bound and NameTree[Name.Bound] so that we can
   * control when residual paths factor into caching.
   */
  class Cached[-Req, +Rsp](
    mkClient: Name.Bound => ServiceFactory[Req, Rsp],
    pathMk: Mk[Dst.Path, Req, Rsp] = Mk.identity[Dst.Path, Req, Rsp],
    boundMk: Mk[Dst.Bound, Req, Rsp] = Mk.identity[Dst.Bound, Req, Rsp],
    namer: NameInterpreter = DefaultInterpreter,
    statsReceiver: StatsReceiver = DefaultStatsReceiver,
    capacity: Capacity = Capacity.default,
    bindingTimeout: BindingTimeout = BindingTimeout.default
  )(implicit timer: Timer = DefaultTimer.twitter) extends DstBindingFactory[Req, Rsp] {
    private[this]type Cache[Key] = ServiceFactoryCache[Key, Req, Rsp]

    def apply(dst: Dst.Path, conn: ClientConnection): Future[Service[Req, Rsp]] = {
      val exc = new RequestTimeoutException(bindingTimeout.timeout, s"binding ${dst.path.show}")
      pathCache(dst, conn).raiseWithin(bindingTimeout.timeout, exc)
    }

    // The path cache is keyed on the resolution context and
    // logical rpc name.  It resolves the name with the Dtab and
    // dispatches connections through the tree cache.
    private[this] val pathCache: Cache[Dst.Path] = {
      def mk(dst: Dst.Path): ServiceFactory[Req, Rsp] = {
        // dtabs aren't available when NoBrokers is thrown so we add them here
        // as well as add a binding timeout
        val dyn = new ServiceFactoryProxy(new DynBoundFactory(dst.bind(namer), treeCache)) {
          override def apply(conn: ClientConnection) = {
            val exc = new RequestTimeoutException(bindingTimeout.timeout, s"dyn binding ${dst.path.show}")
            self(conn).rescue(handleNoBrokers).raiseWithin(bindingTimeout.timeout, exc)
          }

          private val handleNoBrokers: PartialFunction[Throwable, Future[Service[Req, Rsp]]] = {
            case e: NoBrokersAvailableException => nbae
          }

          private lazy val nbae = Future.exception(new NoBrokersAvailableException(
            dst.path.show,
            dst.baseDtab,
            dst.localDtab
          ))
        }

        pathMk(dst, dyn)
      }

      new ServiceFactoryCache(mk, statsReceiver.scope("path"), capacity.paths)
    }

    // The tree cache is effectively keyed on a NameTree of Bound names
    // with their residual paths.
    private[this] val treeCache: Cache[Dst.BoundTree] = {
      def mk(tree: Dst.BoundTree): ServiceFactory[Req, Rsp] =
        NameTreeFactory(tree.path, tree.nameTree, boundCache)

      new ServiceFactoryCache(mk, statsReceiver.scope("tree"), capacity.trees)
    }

    // The bound cache is effectively keyed on the underlying service id
    // and the residual path. It rewrites downstream URIs as requests
    // are dispatched to the underlying client.
    private[this] val boundCache: Cache[Dst.Bound] = {
      def mk(bound: Dst.Bound): ServiceFactory[Req, Rsp] = {
        val client = new ServiceFactory[Req, Rsp] {
          // The client cache doesn't take the residual Path into
          // account, so we strip it here to reduce confusion.
          val name = Name.Bound(bound.addr, bound.id, Path.empty)
          def apply(conn: ClientConnection) = clientCache.apply(name, conn)
          def close(deadline: Time) = Future.Done
          override def status = clientCache.status(name)
        }
        boundMk(bound, client)
      }

      new ServiceFactoryCache(mk, statsReceiver.scope("bound"), capacity.bounds)
    }

    // The bottom cache is effectively keyed on the bound destination id
    // (i.e. concrete service name).
    private[this] val clientCache: Cache[Name.Bound] =
      new ServiceFactoryCache(mkClient, statsReceiver.scope("client"), capacity.clients)

    private[this] val caches: Seq[Cache[_]] =
      Seq(pathCache, treeCache, boundCache, clientCache)

    def close(deadline: Time) =
      Closable.sequence(caches: _*).close(deadline)

    def status = Status.worstOf[Cache[_]](caches, _.status)
  }
}
