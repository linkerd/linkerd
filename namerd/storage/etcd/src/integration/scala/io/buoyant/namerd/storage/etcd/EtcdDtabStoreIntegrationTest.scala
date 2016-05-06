package io.buoyant.namerd.storage.etcd

import com.twitter.finagle.Dtab
import com.twitter.io.Buf
import com.twitter.util.Activity
import io.buoyant.etcd.{Etcd, EtcdFixture}
import io.buoyant.namerd.DtabStore.{DtabNamespaceAlreadyExistsException, DtabNamespaceDoesNotExistException, DtabVersionMismatchException}
import io.buoyant.namerd.{RichActivity, VersionedDtab}
import io.buoyant.test.Exceptions

class EtcdDtabStoreIntegrationTest extends EtcdFixture with Exceptions {

  def mkStore(etcd: Etcd) = {
    val key = etcd.key("/dtabs")
    new EtcdDtabStore(key)
  }

  def extractDtab(obs: Activity[Option[VersionedDtab]]): Dtab =
    await(obs.values.map(_.get.get.dtab).toFuture)

  test("observe follows changes") { etcd =>
    val store = mkStore(etcd)
    val obs = store.observe("hello")
    assert(await(obs.toFuture).isEmpty)
    await(store.create("hello", Dtab.read("/hello => /world")))
    val dtab = await(obs.toFuture).get
    assert(dtab.dtab == Dtab.read("/hello => /world"))
    await(store.update("hello", Dtab.read("/goodbye => /world"), dtab.version))
    val updatedDtab = await(obs.toFuture).get
    assert(updatedDtab.dtab == Dtab.read("/goodbye => /world"))
    assert(updatedDtab.version != dtab.version)
  }

  test("fail to create duplicate namespace") { etcd =>
    val store = mkStore(etcd)
    assert(
      await(store.create("duplicate", Dtab.read("/hello => /world"))).equals(())
    )
    assertThrows[DtabNamespaceAlreadyExistsException] {
      await(store.create("duplicate", Dtab.read("/hello => /world")))
    }
  }

  test("fail to update with incorrect version") { etcd =>
    val store = mkStore(etcd)
    await(store.create("test", Dtab.read("/hello => /world")))
    assertThrows[DtabVersionMismatchException] {
      await(store.update("test", Dtab.read("/hello => /world"), Buf.Utf8("999")))
    }
  }

  test("fail to update non-existent namespace") { etcd =>
    val store = mkStore(etcd)
    assertThrows[DtabNamespaceDoesNotExistException] {
      await(store.update("nothing", Dtab.read("/hello => /world"), Buf.Utf8("1")))
    }
  }

  test("put creates new namespace") { etcd =>
    val store = mkStore(etcd)
    await(store.put("hello", Dtab.read("/hello => /world")))
    assert(
      extractDtab(store.observe("hello")) == Dtab.read("/hello => /world")
    )
  }

  test("put updates existing namespace") { etcd =>
    val store = mkStore(etcd)
    await(store.put("test", Dtab.read("/hello => /world")))
    assert(
      extractDtab(store.observe("test")) == Dtab.read("/hello => /world")
    )
  }

  test("delete deletes dtab") { etcd =>
    val store = mkStore(etcd)
    val obs = store.observe("test")
    assert(await(obs.toFuture).isDefined)
    await(store.delete("test"))
    assert(await(obs.toFuture).isEmpty)
    assert(!await(store.list().toFuture).contains("test"))
  }
}
