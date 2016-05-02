package io.buoyant.namerd

import com.twitter.finagle._
import com.twitter.io.Buf
import com.twitter.util._
import io.buoyant.test.Exceptions
import org.scalatest.FunSuite

class DtabStoreTest extends FunSuite with Exceptions {

  object FailStore extends DtabStore {
    def create(ns: Ns, dtab: Dtab) =
      fail("unexpected create")

    def delete(ns: Ns) =
      fail("unexpected delete")

    def update(ns: Ns, dtab: Dtab, v: DtabStore.Version) =
      fail("unexpected update")

    def put(ns: Ns, dtab: Dtab) =
      fail("unexpected put")

    def list(): Activity[Set[Ns]] =
      fail("unexpected list")

    def observe(ns: Ns) =
      fail("unexpected observe")
  }

  object OkStore extends DtabStore {
    def create(ns: Ns, dtab: Dtab) = Future.Unit
    def delete(ns: Ns) = Future.Unit
    def update(ns: Ns, dtab: Dtab, v: DtabStore.Version) = Future.Unit
    def put(ns: Ns, dtab: Dtab) = Future.Unit
    def list(): Activity[Set[Ns]] = Activity.pending
    def observe(ns: Ns) = Activity.pending
  }

  class Invalid extends Throwable
  def validate(store: DtabStore): DtabStore =
    new DtabStore.Validator(store) {
      protected[this] def validate(ns: Ns, dtab: Dtab) = ns match {
        case "bad" => Future.exception(new Invalid)
        case _ => Future.Unit
      }
    }

  test("validates create: ok") {
    assert(Await.result(validate(OkStore).create("ok", Dtab.empty).liftToTry).isReturn)
  }

  test("validates create: invalid") {
    assertThrows[Invalid] {
      Await.result(validate(FailStore).create("bad", Dtab.empty))
    }
  }

  test("validates update: ok") {
    assert(Await.result(validate(OkStore).update("ok", Dtab.empty, Buf.Empty).liftToTry).isReturn)
  }

  test("validates update: invalid") {
    assertThrows[Invalid] {
      Await.result(validate(FailStore).update("bad", Dtab.empty, Buf.Empty))
    }
  }

  test("validates put: ok") {
    assert(Await.result(validate(OkStore).put("ok", Dtab.empty).liftToTry).isReturn)
  }

  test("validates put: invalid") {
    assertThrows[Invalid] {
      Await.result(validate(FailStore).put("bad", Dtab.empty))
    }
  }

}
