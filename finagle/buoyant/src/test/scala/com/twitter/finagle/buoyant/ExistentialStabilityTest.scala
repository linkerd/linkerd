package com.twitter.finagle.buoyant

import com.twitter.util.{Activity, Var}
import io.buoyant.test.FunSuite
import com.twitter.finagle.buoyant.ExistentialStability._
import org.scalatest.concurrent.Eventually

class ExistentialStabilityTest extends FunSuite with Eventually {

  def waitFor[T, U](v: Var[T])(pred: PartialFunction[T, U]): U = {
    @volatile var value: Option[U] = None
    val obs = v.changes.respond { t =>
      if (pred.isDefinedAt(t)) value = Some(pred(t))
    }
    eventually {
      assert(value.isDefined)
    }
    obs.close()
    value.get
  }

  def assertBecomes[T](v: Var[T], expected: T): Unit = {
    waitFor(v) {
      case t if t == expected => ()
    }
  }

  test("inner var can be independently observed") {
    val unstable = Var[Option[Int]](Some(1))
    val stable = unstable.stabilizeExistence

    val inner = waitFor(stable) {
      case Some(in) => in
    }

    assertBecomes(inner, 1)

    unstable() = Some(2)

    assertBecomes(inner, 2)
  }

  test("inner activity can be independently observed") {
    val underlying = Var[Activity.State[Option[Int]]](Activity.Ok(Some(1)))
    val unstable = Activity(underlying)
    val stable = unstable.stabilizeExistence

    val inner = waitFor(stable.run) {
      case Activity.Ok(Some(in)) => in
    }

    assertBecomes(inner, 1)

    underlying() = Activity.Ok(Some(2))

    assertBecomes(inner, 2)
  }

}
