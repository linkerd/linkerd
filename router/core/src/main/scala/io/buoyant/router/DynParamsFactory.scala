package io.buoyant.router

import com.twitter.finagle
import com.twitter.finagle.Stack.{Leaf, Node}
import com.twitter.finagle._
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.util._

/**
 * Closing a ServiceFactory is difficult to get right; you shouldn't close a ServiceFactory
 * that someone could later try to pull out of a ServiceFactoryCache.
 *
 * This is a module for exercising more precise control on when service factories should be closed.
 * Instead of propogating all closes indiscriminately, this factory only calls close on its underlying
 * ServiceFactory when it's closer Param is resolved.
 */
object HaltClosePropagationFactory {
  def role: Stack.Role = Stack.Role("HaltClose")

  case class Param(closer: Future[Time])
  implicit object Param extends Stack.Param[Param] {
    override def default: Param = Param(new Promise[Time])
  }

  def module[Req, Rep]: Stackable[ServiceFactory[Req, Rep]] = new finagle.Stack.Module1[Param, ServiceFactory[Req, Rep]] {
    override def role: Stack.Role = HaltClosePropagationFactory.role
    override def description: String = "Stop service factory closes from propagating down the stack"
    override def make(
      params: Param,
      next: ServiceFactory[Req, Rep]
    ): ServiceFactory[Req, Rep] =
      new HaltClosePropagationFactory[Req, Rep](next, params.closer)
  }
}

class HaltClosePropagationFactory[Req, Rep](underlying: ServiceFactory[Req, Rep], closer: Future[Time]) extends ServiceFactoryProxy[Req, Rep](underlying) {
  // close the underlying stack if explicitly asked to via closer param
  closer.map(underlying.close)
  // do not propagate normal close requests
  override def close(deadline: Time) = Future.Unit
}

object DynParamsFactory {

  def role: Stack.Role = Stack.Role("DynamicParams")

  case class Param(params: Activity[Stack.Params])
  implicit object Param extends Stack.Param[Param] {
    override def default: Param = Param(Activity.value(Stack.Params.empty))
  }

  def module[Req, Rep]: Stackable[ServiceFactory[Req, Rep]] = new finagle.Stack.Module[ServiceFactory[Req, Rep]] {
    override def role: Stack.Role = DynParamsFactory.role
    override def description: String = "Dynamically reconfigure the stack"
    override def parameters = Seq(implicitly[Stack.Param[Param]])

    // Stack.insertBefore(...) does not work for leaves, so this is a leaf-specific implementation
    def insertBeforeLeaf(stack: Stack[ServiceFactory[Req, Rep]], module: Stackable[ServiceFactory[Req, Rep]]): Stack[ServiceFactory[Req, Rep]] = stack match {
      case Node(hd, mk, next) => Node(hd, mk, insertBeforeLeaf(next, module))
      case Leaf(_, _) => module.toStack(stack)
    }

    override def make(
      params: Stack.Params,
      next: Stack[ServiceFactory[Req, Rep]]
    ): Stack[ServiceFactory[Req, Rep]] = {
      val param.Stats(stats) = params[param.Stats]
      val Param(dynamicParams) = params[Param]
      val dynStats = stats.scope("dynparams")
      //Insert the HaltClosePropagationFactory immediately before the leaf of the stack
      //so that we do not accidentally close DynBoundFactory
      val nextWithHaltClose = insertBeforeLeaf(next, HaltClosePropagationFactory.module[Req, Rep])

      Stack.Leaf(role, new DynParamsFactory(dynamicParams, params, nextWithHaltClose, dynStats))
    }
  }
}

/**
 * DynParamsFactory makes an underlying ServiceFactory each time dynamicParams updates.
 *
 * @param dynamicParams
 * @param params
 * @param next
 * @param dynStats
 */
class DynParamsFactory[Req, Rep](
  dynamicParams: Activity[Stack.Params],
  params: Stack.Params,
  next: Stack[ServiceFactory[Req, Rep]],
  dynStats: StatsReceiver
) extends ServiceFactory[Req, Rep] {
  val updatesCounter = dynStats.counter("updates")
  val closesCounter = dynStats.counter("closes")

  @volatile private[this] var closeLeaf: Promise[Time] = new Promise[Time]
  @volatile var closableUnderlying: Closable = Closable.nop

  val sf = dynamicParams.map { p =>
    synchronized {
      updatesCounter.incr()
      closeLeaf = new Promise[Time]
      // each time the params are updated, make a new ServiceFactory to replace
      // the one currently in use
      val nextFactory = next.make(params ++ p + HaltClosePropagationFactory.Param(closeLeaf))

      closesCounter.incr()
      // close the previous service factory
      closableUnderlying.close()
      closableUnderlying = nextFactory
      nextFactory
    }
  }

  // don't rebuild the ServiceFactory unless we absolutely need to
  val dedupSf = new Activity(Var(Activity.Pending, sf.states.dedup))

  // keep activity open until the ServiceFactory is closed explicitly
  private val obs = dedupSf.states.respond(_ => ())

  // Proxy the ClientConnection to the underlying ServiceFactory
  override def apply(conn: ClientConnection): Future[Service[Req, Rep]] = {
    val toFuture = dedupSf.values.toFuture.flatMap(Future.const)
    toFuture.flatMap(_.apply(conn))
  }

  override def close(deadline: Time): Future[Unit] = {
    synchronized {
      closesCounter.incr()
      closeLeaf.setValue(deadline)
      closableUnderlying.close(deadline)
      obs.close(deadline)
    }
  }
}
