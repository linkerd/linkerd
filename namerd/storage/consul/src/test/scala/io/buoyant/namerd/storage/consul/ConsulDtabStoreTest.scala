package io.buoyant.namerd.storage.consul

import com.twitter.finagle.{Dtab, Path}
import com.twitter.io.Buf
import com.twitter.util.Future
import io.buoyant.consul.v1.{Indexed, Key, KvApi}
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class BaseKvApiStub extends KvApi {
  def get(
    path: String,
    datacenter: Option[String],
    blockingIndex: Option[String],
    recurse: Boolean,
    retry: Boolean
  ): Future[Indexed[Seq[Key]]] = ???

  def put(
    path: String,
    value: String,
    datacenter: Option[String],
    cas: Option[String],
    retry: Boolean
  ): Future[Boolean] = ???

  def delete(
    path: String,
    datacenter: Option[String],
    cas: Option[String],
    recurse: Boolean = false,
    retry: Boolean
  ): Future[Boolean] = ???
}

class ConsulDtabStoreTest extends FunSuite with Awaits {

  test("list returns namespaces with pathPrefix stripped") {

    class ListKvApiStub(keys: Seq[Key]) extends BaseKvApiStub {
      override def get(
        path: String,
        datacenter: Option[String],
        blockingIndex: Option[String],
        recurse: Boolean,
        retry: Boolean
      ): Future[Indexed[Seq[Key]]] = {
        (path, recurse) match {
          case ("/dtabs/", true) if blockingIndex != Some("42") => Future.value(Indexed[Seq[Key]](keys, Some("42")))
          case ("/dtabs/", true) if blockingIndex == Some("42") => Future.never
          case _ => ???
        }
      }
    }

    val api = new ListKvApiStub(Seq(
      Key("dtabs/foo", "/foo=>/bar"),
      Key("dtabs/bar", "/bar=>/baz")
    ))

    val store = new ConsulDtabStore(api, Path.read("/dtabs"))
    val listFuture = store.list().values.map(_.toOption).toFuture()
    val nsSet = await(listFuture).get

    assert(nsSet == Set("foo", "bar"))
  }

  test("observe returns requested dtab value") {

    class ListKvApiStub(keys: Seq[Key]) extends BaseKvApiStub {
      override def get(
        path: String,
        datacenter: Option[String],
        blockingIndex: Option[String],
        recurse: Boolean,
        retry: Boolean
      ): Future[Indexed[Seq[Key]]] = {
        (path, recurse) match {
          case ("/dtabs/foo", false) if blockingIndex != Some("42") => Future.value(Indexed[Seq[Key]](keys, Some("42")))
          case ("/dtabs/foo", false) if blockingIndex == Some("42") => Future.never
          case _ => ???
        }
      }
    }

    val api = new ListKvApiStub(Seq(Key("dtabs/foo", "/foo=>/bar")))

    val store = new ConsulDtabStore(api, Path.read("/dtabs"))
    val dtabFuture = store.observe("foo").toFuture
    val versionedDtab = await(dtabFuture).get

    assert(versionedDtab.version == Buf.Utf8("42"))
    assert(versionedDtab.dtab == Dtab.read("/foo=>/bar"))
  }

  test("observe reports broken dtabs as empty") {

    class ListKvApiStub(keys: Seq[Key]) extends BaseKvApiStub {
      override def get(
        path: String,
        datacenter: Option[String],
        blockingIndex: Option[String],
        recurse: Boolean,
        retry: Boolean
      ): Future[Indexed[Seq[Key]]] = {
        (path, recurse) match {
          case ("/dtabs/foo", false) if blockingIndex != Some("42") => Future.value(Indexed[Seq[Key]](keys, Some("42")))
          case ("/dtabs/foo", false) if blockingIndex == Some("42") => Future.never
          case _ => ???
        }
      }
    }

    val api = new ListKvApiStub(Seq(Key("dtabs/foo", "broken")))

    val store = new ConsulDtabStore(api, Path.read("/dtabs"))
    val dtabFuture = store.observe("foo").toFuture
    val versionedDtab = await(dtabFuture).get

    assert(versionedDtab.version == Buf.Utf8("42"))
    assert(versionedDtab.dtab == Dtab.empty)
  }

}
