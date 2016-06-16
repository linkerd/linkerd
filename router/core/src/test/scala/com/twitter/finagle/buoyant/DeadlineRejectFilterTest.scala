package com.twitter.finagle.buoyant

import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.context.Contexts
import io.buoyant.test.{Awaits, Exceptions}
import org.scalatest.FunSuite
import com.twitter.util.{Duration, Future, Promise, Stopwatch, Time, MockTimer}

class DeadlineRejectFilterTest extends FunSuite with Awaits with Exceptions {

  def mkstk(svc: String => Future[String]) =
    DeadlineRejectFilter.module[String, String]
      .toStack(Stack.Leaf(Stack.Role("ep"), ServiceFactory.const(Service.mk(svc))))

  test("by default, rejects requests that exceed the deadline once failure budget is established") {
    val stk = mkstk(Future.value(_))
    val timer = new MockTimer
    val params = Stack.Params.empty + param.Timer(timer)
    val svc = await(stk.make(params)())
    Time.withCurrentTimeFrozen { clock =>
      Contexts.broadcast.let(Deadline, Deadline(Time.now, Time.now + 1.second)) {
        clock.advance(1.second)
        timer.tick()
        // At 20% budget, need to issue 5 requests to build up budget
        // such that a request can be failed.
        assert(await(svc("0")) == "0")
        assert(await(svc("1")) == "1")
        assert(await(svc("2")) == "2")
        assert(await(svc("3")) == "3")
        val failure = intercept[Failure] {
          val _ = await(svc("4"))
        }
        assert(failure.isFlagged(Failure.Restartable))
        // budget exhausted, back to allowing requests
        assert(await(svc("5")) == "5")
      }
    }
  }

  test("by default, uses deadline as a timeout") {
    val promises = (0 to 5).map(_ -> new Promise[String]).toMap
    val stk = mkstk { i => promises(i.toInt) }
    val timer = new MockTimer
    val params = Stack.Params.empty + param.Timer(timer)
    val svc = await(stk.make(params)())
    Time.withCurrentTimeFrozen { clock =>
      Contexts.broadcast.let(Deadline, Deadline(Time.now, Time.now + 2.second)) {
        // At 20% budget, need to issue 5 requests to build up budget
        // such that a request can be failed.
        def satisfy(n: Int): Unit = {
          val f = svc(n.toString)
          promises(n).setValue("ok")
          assert(await(f) == "ok")
        }

        (0 to 3).foreach(satisfy(_))
        val f4 = svc("4")
        val f5 = svc("5")
        clock.advance(2.seconds)
        timer.tick()
        assertThrows[GlobalRequestTimeoutException] {
          await(f4)
        }
        promises(5).setValue("ok")
        assert(await(f5) == "ok")
      }
    }
  }
}
