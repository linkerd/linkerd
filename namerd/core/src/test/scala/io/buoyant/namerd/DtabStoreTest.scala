package io.buoyant.namerd

import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.io.Buf
import com.twitter.util._
import org.scalatest.FunSuite

class DtabStoreTest extends FunSuite {

  object FailStore extends DtabStore {
    def create(ns: DtabStore.Namespace, dtab: Dtab) =
      fail("unexpected create")

    def update(ns: DtabStore.Namespace, dtab: Dtab, v: DtabStore.Version) =
      fail("unexpected update")

    def put(ns: DtabStore.Namespace, dtab: Dtab) =
      fail("unexpected put")

    def list() =
      fail("unexpected list")

    def observe(ns: DtabStore.Namespace) =
      fail("unexpected observe")
  }

  object OkStore extends DtabStore {
    def create(ns: DtabStore.Namespace, dtab: Dtab) = Future.Unit
    def update(ns: DtabStore.Namespace, dtab: Dtab, v: DtabStore.Version) = Future.Unit
    def put(ns: DtabStore.Namespace, dtab: Dtab) = Future.Unit
    def list() = Future.value(Set.empty)
    def observe(ns: DtabStore.Namespace) = Activity.pending
  }

  class Invalid extends Throwable
  def validate(store: DtabStore): DtabStore =
    new DtabStore.Validator(store) {
      protected[this] def validate(ns: DtabStore.Namespace, dtab: Dtab) = ns match {
        case "bad" => Future.exception(new Invalid)
        case _ => Future.Unit
      }
    }

  test("validates create: ok") {
    assert(Await.result(validate(OkStore).create("ok", Dtab.empty).liftToTry).isReturn)
  }

  test("validates create: invalid") {
    intercept[Invalid] {
      Await.result(validate(FailStore).create("bad", Dtab.empty))
    }
  }

  test("validates update: ok") {
    assert(Await.result(validate(OkStore).update("ok", Dtab.empty, Buf.Empty).liftToTry).isReturn)
  }

  test("validates update: invalid") {
    intercept[Invalid] {
      Await.result(validate(FailStore).update("bad", Dtab.empty, Buf.Empty))
    }
  }

  test("validates put: ok") {
    assert(Await.result(validate(OkStore).put("ok", Dtab.empty).liftToTry).isReturn)
  }

  test("validates put: invalid") {
    intercept[Invalid] {
      Await.result(validate(FailStore).put("bad", Dtab.empty))
    }
  }

}
