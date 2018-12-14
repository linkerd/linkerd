package io.buoyant.namer

import com.twitter.conversions.DurationOps._
import com.twitter.util._
import io.buoyant.test.FunSuite

class InstrumentedVarTest extends FunSuite {

  test("start, update, stop") {
    @volatile var running = false

    val updateP = new Promise[Int]

    val iv = InstrumentedVar(0) { up =>
      running = true
      updateP.onSuccess(up.update)
      Closable.make { _ =>
        running = false
        Future.Unit
      }
    }

    Time.withCurrentTimeFrozen { tc =>

      assert(iv.stateSnapshot() == VarState(
        running = false,
        lastStartedAt = None,
        lastUpdatedAt = None,
        lastStoppedAt = None,
        value = 0
      ))
      assert(!running)

      val closable = iv.underlying.changes.respond(_ => ())
      val startedAt = Time.now

      assert(iv.stateSnapshot() == VarState(
        running = true,
        lastStartedAt = Some(startedAt.toString),
        lastUpdatedAt = None,
        lastStoppedAt = None,
        value = 0
      ))
      assert(running)

      tc.advance(1.second)

      updateP.setValue(100)
      val updatedAt = Time.now

      assert(iv.stateSnapshot() == VarState(
        running = true,
        lastStartedAt = Some(startedAt.toString),
        lastUpdatedAt = Some(updatedAt.toString),
        lastStoppedAt = None,
        value = 100
      ))
      assert(running)

      tc.advance(1.second)

      await(closable.close())
      val closedAt = Time.now

      assert(iv.stateSnapshot() == VarState(
        running = false,
        lastStartedAt = Some(startedAt.toString),
        lastUpdatedAt = Some(updatedAt.toString),
        lastStoppedAt = Some(closedAt.toString),
        value = 100
      ))
      assert(!running)
    }
  }
}

class InstrumentedActivityTest extends FunSuite {

  test("start, update, stop") {
    @volatile var running = false

    val updateP = new Promise[Int]

    val iv = InstrumentedActivity[Int] { up =>
      running = true
      updateP.onSuccess { i => up.update(Activity.Ok(i)) }
      Closable.make { _ =>
        running = false
        Future.Unit
      }
    }

    Time.withCurrentTimeFrozen { tc =>

      assert(iv.stateSnapshot() == VarState(
        running = false,
        lastStartedAt = None,
        lastUpdatedAt = None,
        lastStoppedAt = None,
        value = None
      ))
      assert(!running)

      val closable = iv.underlying.states.respond(_ => ())
      val startedAt = Time.now

      assert(iv.stateSnapshot() == VarState(
        running = true,
        lastStartedAt = Some(startedAt.toString),
        lastUpdatedAt = None,
        lastStoppedAt = None,
        value = None
      ))
      assert(running)

      tc.advance(1.second)

      updateP.setValue(100)
      val updatedAt = Time.now

      assert(iv.stateSnapshot() == VarState(
        running = true,
        lastStartedAt = Some(startedAt.toString),
        lastUpdatedAt = Some(updatedAt.toString),
        lastStoppedAt = None,
        value = Some(100)
      ))
      assert(running)

      tc.advance(1.second)

      await(closable.close())
      val closedAt = Time.now

      assert(iv.stateSnapshot() == VarState(
        running = false,
        lastStartedAt = Some(startedAt.toString),
        lastUpdatedAt = Some(updatedAt.toString),
        lastStoppedAt = Some(closedAt.toString),
        value = Some(100)
      ))
      assert(!running)
    }
  }
}
