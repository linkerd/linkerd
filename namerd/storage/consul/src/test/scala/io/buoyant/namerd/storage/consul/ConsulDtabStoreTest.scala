package io.buoyant.namerd.storage.consul

import com.twitter.finagle.{Dtab, Path}
import com.twitter.io.Buf
import com.twitter.util.Future
import io.buoyant.consul.v1.{Indexed, Key, KvApi}
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class ConsulDtabStoreTest extends FunSuite with Awaits {

  class KvApiStub(keys: Seq[Key]) extends KvApi {
    override def get(
      path: String,
      datacenter: Option[String],
      blockingIndex: Option[String],
      recurse: Boolean,
      retry: Boolean
    ): Future[Indexed[Seq[Key]]] = {
      (path, recurse) match {
        case ("/dtabs/mydtab", true) if blockingIndex != Some("42") =>
          Future.value(Indexed[Seq[Key]](keys, Some("42")))
        case ("/dtabs/mydtab", true) if blockingIndex == Some("42") =>
          Future.never
        case _ => ???
      }
    }

    override def list(
      path: String,
      datacenter: Option[String],
      blockingIndex: Option[String],
      separator: Option[String],
      retry: Boolean
    ): Future[Indexed[Seq[String]]] = ???

    override def put(
      path: String,
      value: String,
      datacenter: Option[String],
      cas: Option[String],
      retry: Boolean
    ): Future[Boolean] = ???

    override def delete(
      path: String,
      datacenter: Option[String],
      cas: Option[String],
      recurse: Boolean = false,
      retry: Boolean
    ): Future[Boolean] = ???
  }

  test("observe in recursive mode merges Dtabs alphabetically") {
    val api = new KvApiStub(Seq(
      Key("dtabs/mydtab/b", "/5th=>/line"),
      Key("dtabs/mydtab/a", "/2nd=>/line"),
      Key("dtabs/mydtab/a/b", "/4th=>/line"),
      Key("dtabs/mydtab/a/a", "/3rd=>/line"),
      Key("dtabs/mydtab", "/1st=>/line"),
      Key("dtabs/mydtab/x", "invalid value"),
      Key(Some("dtabs/mydtab/empty"), None)
    ))

    val store = new ConsulDtabStore(api, Path.read("/dtabs"), recursive = true)
    val act = store.observe("mydtab/")

    val dtab = await(act.toFuture)
    assert(dtab.isDefined)

    val dtabValue = dtab.get
    assert(dtabValue.version == Buf.Utf8("42"))
    assert(dtabValue.dtab == Dtab.read(
      """
        |/1st=>/line;
        |/2nd=>/line;
        |/3rd=>/line;
        |/4th=>/line;
        |/5th=>/line;
      """.stripMargin
    ))
  }

  test("observe matches keys by segments between slashes (/)") {
    val api = new KvApiStub(Seq(
      Key("dtabs/mydtab", "/1st=>/line"),
      Key("dtabs/mydtab/ext", "/2nd=>/line"),
      Key("dtabs/mydtab2", "/fiz=>/baz")
    ))

    val store = new ConsulDtabStore(api, Path.read("/dtabs"), recursive = true)
    val act = store.observe("mydtab/")

    val dtab = await(act.toFuture)
    assert(dtab.isDefined)

    val dtabValue = dtab.get
    assert(dtabValue.version == Buf.Utf8("42"))
    assert(dtabValue.dtab == Dtab.read(
      """
        |/1st=>/line;
        |/2nd=>/line;
      """.stripMargin
    ))
  }

}
