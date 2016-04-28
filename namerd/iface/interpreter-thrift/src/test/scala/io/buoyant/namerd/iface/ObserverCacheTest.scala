package io.buoyant.namerd.iface

import com.twitter.io.Buf
import com.twitter.util._
import io.buoyant.namerd.iface.ThriftNamerInterface.{Stamp, Observer}
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

  test("observer cache caches") {

    var count = 0

    val cache = new ObserverCache[String, String](1, 0)({ _ =>
      count += 1
      testObserver[String]().obs
    })

    assert(cache.get("one").isReturn)
    assert(cache.get("one").isReturn)
    assert(cache.get("one").isReturn)
    assert(cache.get("one").isReturn)
    assert(count == 1)
  }

  test("observer cache limits active observations") {

    val cache = new ObserverCache[String, String](2, 0)(_ => testObserver().obs)

    // insert "one" and "two" into the active cache
    assert(cache.get("one").isReturn)
    assert(cache.get("two").isReturn)
    // active cache is full
    assert(cache.get("three").isThrow)
  }

  test("observer cache deactivates observations") {

    val one = testObserver[String]()
    val two = testObserver[String]()

    val cache = new ObserverCache[String, String](1, 1)({
      case "one" => one.obs
      case "two" => two.obs
    })

    // insert "one" into the active cache
    assert(cache.get("one").get eq one.obs)
    // update deactivates "one"
    one.event.notify(Return("foo"))
    assert(one.closed.sample == false)
    // with "one" removed from the active cache, there is room to insert "two"
    assert(cache.get("two").get eq two.obs)
  }

  test("observer cache reactivates observations") {

    val one = testObserver[String]()

    val cache = new ObserverCache[String, String](1, 1)({
      case "one" => one.obs
    })

    // insert "one" into the active cache
    assert(cache.get("one").get eq one.obs)
    // update deactivates "one"
    one.event.notify(Return("foo"))
    assert(one.closed.sample == false)
    // reactivate "one"
    assert(cache.get("one").get eq one.obs)
    // active cache is full
    assert(cache.get("two").isThrow)
  }

  test("observer cache tears down inactive observations") {

    val one = testObserver[String]()
    val two = testObserver[String]()

    val cache = new ObserverCache[String, String](2, 1)({
      case "one" => one.obs
      case "two" => two.obs
    })

    // insert "one" into the active cache
    assert(cache.get("one").get eq one.obs)
    // insert "two" into the active cache
    assert(cache.get("two").get eq two.obs)
    // update deactivates "one"
    one.event.notify(Return("foo"))
    assert(one.closed.sample == false)
    // update deactivates "two"
    two.event.notify(Return("foo"))
    assert(two.closed.sample == false)
    // "one" is evicted from inactive cache and torn down
    assert(one.closed.sample == true)
  }
}
