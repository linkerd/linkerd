package io.buoyant.namerd.iface

import com.twitter.conversions.DurationOps._
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.io.Buf
import com.twitter.util._
import io.buoyant.namerd.iface.ThriftNamerInterface.{Observer, Stamp}
import org.scalatest.FunSuite

class ObserverCacheTest extends FunSuite {

  case class TestObserver[T](obs: Observer[T], event: Event[Try[T]] with Witness[Try[T]], closed: Var[Boolean])

  def testObserver[T](): TestObserver[T] = {
    val event = Event[Try[T]]
    val closed = Var(false)
    val obs = new Observer[T] {
      var i = 0

      protected[this] def nextStamp(): Stamp = {
        val stamp = Buf.Utf8(i.toString)
        i += 1
        stamp
      }

      protected[this] val updater: Closable = {
        val eventClosable = event.respond(update)
        Closable.all(
          eventClosable,
          Closable.make { _ => closed() = true; Future.Unit }
        )
      }
    }
    TestObserver(obs, event, closed)
  }

  private[this] def activeSize(stats: InMemoryStatsReceiver): Int =
    stats.gauges(Seq("active"))().toInt
  private[this] def inactiveSize(stats: InMemoryStatsReceiver): Int =
    stats.gauges(Seq("inactive"))().toInt

  test("observer cache caches") {

    var count = 0

    val stats = new InMemoryStatsReceiver
    val cache = new ObserverCache[String, String](1, 0, Int.MaxValue, stats, { _: String =>
      count += 1
      testObserver[String]().obs
    })

    assert(cache.get("one").isReturn)
    assert(cache.get("one").isReturn)
    assert(cache.get("one").isReturn)
    assert(cache.get("one").isReturn)
    assert(count == 1)
    assert(activeSize(stats) == 1)
    assert(inactiveSize(stats) == 0)
  }

  test("observer cache limits active observations") {

    val stats = new InMemoryStatsReceiver
    val cache = new ObserverCache[String, String](2, 0, Int.MaxValue, stats, _ => testObserver().obs)

    // insert "one" and "two" into the active cache
    assert(cache.get("one").isReturn)
    assert(cache.get("two").isReturn)
    assert(activeSize(stats) == 2)
    // active cache is full
    assert(cache.get("three").isThrow)
    assert(activeSize(stats) == 2)
    assert(inactiveSize(stats) == 0)
  }

  test("observer cache deactivates observations") {

    val one = testObserver[String]()
    val two = testObserver[String]()

    val stats = new InMemoryStatsReceiver
    val cache = new ObserverCache[String, String](1, 1, Int.MaxValue, stats, {
      case "one" => one.obs
      case "two" => two.obs
    })

    // insert "one" into the active cache
    assert(cache.get("one").get eq one.obs)
    assert(activeSize(stats) == 1)
    assert(inactiveSize(stats) == 0)
    // update deactivates "one"
    one.event.notify(Return("foo"))
    assert(one.closed.sample == false)
    assert(activeSize(stats) == 0)
    assert(inactiveSize(stats) == 1)
    // with "one" removed from the active cache, there is room to insert "two"
    assert(cache.get("two").get eq two.obs)
    assert(activeSize(stats) == 1)
    assert(inactiveSize(stats) == 1)
  }

  test("observer cache reactivates observations") {

    val one = testObserver[String]()

    val stats = new InMemoryStatsReceiver
    val cache = new ObserverCache[String, String](1, 1, Int.MaxValue, stats, {
      case "one" => one.obs
    })

    // insert "one" into the active cache
    assert(cache.get("one").get eq one.obs)
    assert(activeSize(stats) == 1)
    assert(inactiveSize(stats) == 0)
    // update deactivates "one"
    one.event.notify(Return("foo"))
    assert(one.closed.sample == false)
    assert(activeSize(stats) == 0)
    assert(inactiveSize(stats) == 1)
    // reactivate "one"
    assert(cache.get("one").get eq one.obs)
    assert(one.closed.sample == false)
    assert(activeSize(stats) == 1)
    assert(inactiveSize(stats) == 0)
    // active cache is full
    assert(cache.get("two").isThrow)
    assert(activeSize(stats) == 1)
    assert(inactiveSize(stats) == 0)
  }

  test("observer cache tears down inactive observations") {

    val one = testObserver[String]()
    val two = testObserver[String]()

    val stats = new InMemoryStatsReceiver
    val cache = new ObserverCache[String, String](2, 1, Int.MaxValue, stats, {
      case "one" => one.obs
      case "two" => two.obs
    })

    // insert "one" into the active cache
    assert(cache.get("one").get eq one.obs)
    assert(activeSize(stats) == 1)
    assert(inactiveSize(stats) == 0)
    // insert "two" into the active cache
    assert(cache.get("two").get eq two.obs)
    assert(activeSize(stats) == 2)
    assert(inactiveSize(stats) == 0)
    // update deactivates "one"
    one.event.notify(Return("foo"))
    assert(one.closed.sample == false)
    assert(activeSize(stats) == 1)
    assert(inactiveSize(stats) == 1)
    // update deactivates "two"
    two.event.notify(Return("foo"))
    assert(two.closed.sample == false)
    // "one" is evicted from inactive cache and torn down
    assert(one.closed.sample == true)
    assert(activeSize(stats) == 0)
    assert(inactiveSize(stats) == 1)
  }

  test("observer cache removes inactive observations after ttl time") {

    val one = testObserver[String]()
    val stats = new InMemoryStatsReceiver
    val ttl = 1.second

    Time.withCurrentTimeFrozen {
      tc =>
        val cache = new ObserverCache[String, String](1, 5, ttl.inSeconds, stats, {
          case "one" => one.obs
        }, Stopwatch.timeNanos)

        // insert "one" into the active cache
        assert(cache.get("one").get eq one.obs)
        assert(activeSize(stats) == 1)
        assert(inactiveSize(stats) == 0)
        // update deactivates "one"
        one.event.notify(Return("foo"))

        assert(activeSize(stats) == 0)
        assert(inactiveSize(stats) == 1)
        assert(!one.closed.sample)

        tc.advance(ttl / 2)
        cache.inactiveCacheCleanup()
        assert(activeSize(stats) == 0)
        assert(inactiveSize(stats) == 1)
        assert(!one.closed.sample)

        tc.advance(ttl / 2)
        cache.inactiveCacheCleanup()
        assert(activeSize(stats) == 0)
        assert(inactiveSize(stats) == 0)
        assert(one.closed.sample)
    }
  }
}
