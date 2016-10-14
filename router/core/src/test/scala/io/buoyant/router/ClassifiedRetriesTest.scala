package io.buoyant.router

import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.service._
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.finagle.tracing._
import com.twitter.util.{Duration, Future, Return, Throw, Time, Try, MockTimer}
import io.buoyant.test.{Exceptions, Awaits}
import org.scalatest.FunSuite

class ClassifiedRetriesTest extends FunSuite with Awaits with Exceptions {

  class Badness extends Exception

  class Ctx {
    val stats = new InMemoryStatsReceiver
    val timer = new MockTimer
    val tracer = new BufferingTracer
    var backoffs = 1.second #:: 2.seconds #:: Stream.Empty
    var budget = RetryBudget()
    private val classifier = ResponseClassifier.named("test") {
      case ReqRep("retry", Throw(_: Badness)) => ResponseClass.RetryableFailure
      case ReqRep(_, Return(_)) => ResponseClass.Success
      case ReqRep(_, Throw(_)) => ResponseClass.NonRetryableFailure
    }

    @volatile var nextValue: Try[Int] = Throw(new IllegalArgumentException)
    private val stk = ClassifiedRetries.module[String, Int] +: Stack.Leaf(
      stack.Endpoint,
      ServiceFactory.const(Service.mk[String, Int](_ => Future.const((nextValue))))
    )

    private lazy val params = Stack.Params.empty +
      param.Stats(stats) +
      param.HighResTimer(timer) +
      ClassifiedRetries.Backoffs(backoffs) +
      Retries.Budget(budget) +
      param.ResponseClassifier(classifier)
    lazy val _svc = await(stk.make(params).apply())
    val svc = Service.mk[String, Int] { s =>
      Trace.letTracer(tracer) { _svc(s) }
    }
  }

  test("successful request") {
    val ctx = new Ctx
    import ctx._

    // issuing a normal request works as expected
    nextValue = Return(0)
    assert(await(svc("ok")) == 0)
    assert(stats.counters(Seq("retries")) == 0)
    assert(tracer.iterator.map(_.annotation).toSeq == Seq.empty)
  }

  test("requests fail immediately") {
    val ctx = new Ctx
    import ctx._

    // issuing a normal request works as expected
    nextValue = Throw(new Badness)
    assertThrows[Badness] { await(svc("ok")) }
    assert(stats.counters(Seq("retries")) == 0)
    assert(tracer.iterator.map(_.annotation).toSeq == Seq.empty)
  }

  test("retry backoffs succeed") {
    val ctx = new Ctx
    import ctx._

    Time.withCurrentTimeFrozen { clock =>
      nextValue = Throw(new Badness)
      val f = svc("retry")
      assert(!f.isDefined)
      assert(stats.stats == Map.empty)

      clock.advance(1.second)
      timer.tick()
      assert(!f.isDefined)
      assert(stats.stats == Map.empty)

      clock.advance(2.second - 1.millisecond)
      timer.tick()
      assert(!f.isDefined)
      assert(stats.stats == Map.empty)

      nextValue = Return(2)
      clock.advance(1.millisecond)
      timer.tick()
      assert(f.isDefined)
      assert(await(f) == 2)
      assert(stats.counters(Seq("retries")) == 2)
      assert(tracer.iterator.map(_.annotation).toSeq ==
        Seq(Annotation.Message("finagle.retry"), Annotation.Message("finagle.retry")))
    }
  }

  test("retry backoffs exhausted") {
    val ctx = new Ctx
    import ctx._

    Time.withCurrentTimeFrozen { clock =>
      nextValue = Throw(new Badness)
      val f = svc("retry")
      assert(!f.isDefined)
      assert(stats.stats == Map.empty)

      clock.advance(1.second)
      timer.tick()
      assert(!f.isDefined)
      assert(stats.stats == Map.empty)

      clock.advance(2.second - 1.millisecond)
      timer.tick()
      assert(!f.isDefined)
      assert(stats.stats == Map.empty)

      clock.advance(1.millisecond)
      timer.tick()
      assertThrows[Badness] { await(f) }
      assert(stats.counters(Seq("retries")) == 2)
      assert(tracer.iterator.map(_.annotation).toSeq ==
        Seq(Annotation.Message("finagle.retry"), Annotation.Message("finagle.retry")))
    }
  }

  test("retry budget exhausted") {
    val ctx = new Ctx
    import ctx._
    backoffs = Backoff.const(10.millis)
    budget = RetryBudget(10.seconds, 0, 2.0)

    Time.withCurrentTimeFrozen { clock =>
      nextValue = Throw(new Badness)
      val f = svc("retry")
      assert(!f.isDefined)
      assert(stats.stats == Map.empty)

      clock.advance(10.millis)
      timer.tick()
      assert(!f.isDefined)
      assert(stats.stats == Map.empty)

      clock.advance(10.millis)
      timer.tick()
      assert(f.isDefined)
      assertThrows[Badness] { await(f) }
      assert(stats.counters(Seq("retries")) == 2)
      assert(tracer.iterator.map(_.annotation).toSeq ==
        Seq(Annotation.Message("finagle.retry"), Annotation.Message("finagle.retry")))
    }
  }
}
