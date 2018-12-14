package io.buoyant.namerd.storage

import com.twitter.conversions.DurationOps._
import com.twitter.finagle.Dtab
import com.twitter.util.{Activity, Await}
import io.buoyant.namer.RichActivity
import io.buoyant.namerd.DtabStore.{DtabNamespaceAlreadyExistsException, DtabNamespaceDoesNotExistException, DtabVersionMismatchException}
import io.buoyant.namerd.{NamerdConfig, TestNamerInterfaceInitializer, VersionedDtab}
import io.buoyant.test.Exceptions
import org.scalatest.FunSuite

class InMemoryDtabStoreTest extends FunSuite with Exceptions {

  def extractDtab(obs: Activity[Option[VersionedDtab]]): Dtab =
    Await.result(obs.values.map(_.get.get.dtab).toFuture)

  def mkStore = {
    val yaml = """
                 |storage:
                 |  kind: io.l5d.inMemory
                 |  namespaces:
                 |    test: |
                 |      /foo => /bar;
                 |namers: []
                 |interfaces:
                 |- kind: test
               """.stripMargin

    val config = NamerdConfig
      .loadNamerd(yaml, NamerdConfig.Initializers(
        dtabStore = Seq(new InMemoryDtabStoreInitializer),
        iface = Seq(TestNamerInterfaceInitializer)
      ))
    val namerd = config.mk()
    val values = namerd.dtabStore.observe("test").values.map(_.get)
    val dtab = Await.result(values.toFuture(), 1.second)
    assert(dtab.get.dtab == Dtab.read("/foo => /bar"))
    namerd.dtabStore
  }

  test("observe follows changes") {
    val store = mkStore.asInstanceOf[InMemoryDtabStore]
    val obs = store.observe("hello")
    assert(obs.sample.isEmpty)
    Await.result(store.create("hello", Dtab.read("/hello => /world")))
    assert(obs.sample.get.dtab == Dtab.read("/hello => /world"))
    val version = obs.sample.get.version
    Await.result(store.update("hello", Dtab.read("/goodbye => /world"), version))
    assert(obs.sample.get.dtab == Dtab.read("/goodbye => /world"))
  }

  test("fail to create duplicate namespace") {
    val store = mkStore
    assert(
      Await.result(store.create("hello", Dtab.read("/hello => /world"))).equals(())
    )
    assertThrows[DtabNamespaceAlreadyExistsException] {
      Await.result(store.create("hello", Dtab.read("/hello => /world")))
    }
  }

  test("fail to update with incorrect version") {
    val store = mkStore
    assertThrows[DtabVersionMismatchException] {
      Await.result(store.update("test", Dtab.read("/hello => /world"), InMemoryDtabStore.version(0)))
    }
  }

  test("fail to update non-existent namespace") {
    val store = mkStore
    assertThrows[DtabNamespaceDoesNotExistException] {
      Await.result(store.update("nothing", Dtab.read("/hello => /world"), InMemoryDtabStore.version(1)))
    }
  }

  test("put creates new namespace") {
    val store = mkStore
    Await.result(store.put("hello", Dtab.read("/hello => /world")))
    assert(
      extractDtab(store.observe("hello")) == Dtab.read("/hello => /world")
    )
  }

  test("put updates existing namespace") {
    val store = mkStore
    Await.result(store.put("test", Dtab.read("/hello => /world")))
    assert(
      extractDtab(store.observe("test")) == Dtab.read("/hello => /world")
    )
  }

  test("delete deletes dtab") {
    val store = mkStore
    val obs = store.observe("test")
    assert(obs.sample.isDefined)
    Await.result(store.delete("test"))
    assert(obs.sample.isEmpty)
    assert(!Await.result(store.list().toFuture).contains("test"))
  }
}
