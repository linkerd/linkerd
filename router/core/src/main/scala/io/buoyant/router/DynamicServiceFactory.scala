package io.buoyant.router

import com.twitter.finagle
import com.twitter.finagle._
import com.twitter.util.{Activity, Future, Time, Var}

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
      val Param(dynamicParams) = params[Param]
      val sf = dynamicParams.map { p =>
        next.make(params ++ p)
      }
      Stack.Leaf(role, new DynamicServiceFactory(sf))
    }
  }
}

class DynamicServiceFactory[Req, Rep](sf: Activity[ServiceFactory[Req, Rep]]) extends ServiceFactory[Req, Rep] {
  val dedupSf = new Activity(Var(Activity.Pending, sf.states.dedup))
  private lazy val obs = dedupSf.states.respond(_ => ())
  obs.hashCode() // start observing the Activity

  override def apply(conn: ClientConnection): Future[Service[Req, Rep]] = {
    dedupSf.values.toFuture.flatMap(Future.const).flatMap(_.apply(conn))
  }

  override def close(deadline: Time): Future[Unit] = obs.close(deadline)
}
