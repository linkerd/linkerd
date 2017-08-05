package io.buoyant.router

import com.twitter.finagle
import com.twitter.finagle._
import com.twitter.util._

object DynamicServiceFactory {

  def role: Stack.Role = Stack.Role("dynamicParams")

  case class Param(params: Activity[Stack.Params])
  implicit object Param extends Stack.Param[Param] {
    override def default: Param = Param(Activity.value(Stack.Params.empty))
  }

  def module[Req, Rep]: Stackable[ServiceFactory[Req, Rep]] = new finagle.Stack.Module[ServiceFactory[Req, Rep]] {

    override def role: Stack.Role = DynamicServiceFactory.role

    override def description: String = "dynamically reconfigure the stack"

    override def parameters = Seq(implicitly[Stack.Param[Param]])

    override def make(
      params: Stack.Params,
      next: Stack[ServiceFactory[Req, Rep]]
    ): Stack[ServiceFactory[Req, Rep]] = {
      val param.Stats(stats) = params[param.Stats]
      val dynStats = stats.scope("dynsvcfactory").counter("updates")

      val Param(dynamicParams) = params[Param]
      val sf = dynamicParams.map { p =>
        dynStats.incr()
        // each time the params are updated, make a new ServiceFactory to replace
        // the one currently in use
        next.make(params ++ p)
      }
      Stack.Leaf(role, new DynamicServiceFactory(sf))
    }
  }
}

/**
 * DynamicServiceFactory wraps a ServiceFactory Activity.
 * @param sf A ServiceFactory Activity that updates when it's underlying Stack.Params update.
 */
class DynamicServiceFactory[Req, Rep](sf: Activity[ServiceFactory[Req, Rep]]) extends ServiceFactory[Req, Rep] {
  @volatile var closable: Closable = Closable.nop
  // don't rebuild the ServiceFactory unless we absolutely need to
  val dedupSf = new Activity(Var(Activity.Pending, sf.states.dedup))
  // keep activity open until the ServiceFactory is closed explicitly
  private val obs = dedupSf.states.respond {
    case Activity.Ok(sf) =>
      // close the previous service factory
      closable.close()
      closable = sf
    case _ =>
  }

  /**
   * Proxy the ClientConnection to the underlying ServiceFactory
   */
  override def apply(conn: ClientConnection): Future[Service[Req, Rep]] = {
    val toFuture = dedupSf.values.toFuture.flatMap(Future.const)
    toFuture.flatMap(_.apply(conn))
  }

  override def close(deadline: Time): Future[Unit] = {
    closable.close(deadline)
    obs.close(deadline)
  }
}
