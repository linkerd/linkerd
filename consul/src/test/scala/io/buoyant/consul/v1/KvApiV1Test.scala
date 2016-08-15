package io.buoyant.consul.v1

import com.twitter.conversions.time._
import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response}
import com.twitter.io.Buf
import com.twitter.util.Future
import io.buoyant.test.{Awaits, Exceptions}
import org.scalatest.FunSuite

class KvApiV1Test extends FunSuite with Awaits with Exceptions {
  val getBuf = Buf.Utf8("""[{"LockIndex":0,"Key":"sample","Flags":0,"Value":"Zm9vYmFy","CreateIndex":10,"ModifyIndex":12}]""")
  val putOkBuf = Buf.Utf8("""true""")
  val putFailBuf = Buf.Utf8("""false""")
  val deleteOkBuf = Buf.Utf8("""true""")
  val deleteFailBuf = Buf.Utf8("""false""")

  var lastUri = ""

  override val defaultWait = 2.seconds

  def stubService(buf: Buf) = Service.mk[Request, Response] { req =>
    val rsp = Response()
    rsp.setContentTypeJson()
    rsp.content = buf
    rsp.headerMap.set("X-Consul-Index", "4")
    lastUri = req.uri
    Future.value(rsp)
  }

  test("get returns an indexed seq of values") {
    val service = stubService(getBuf)

    val result = await(KvApiV1(service).get("/sample"))
    assert(result.index == Some("4"))
    assert(result.value.size == 1)
    assert(result.value.head.decoded == Some("foobar"))
  }

  test("get by default is non-recurse") {
    val service = stubService(getBuf)

    await(KvApiV1(service).get("/path/to/key"))
    assert(!lastUri.contains("recurse"))
  }

  test("get with recurse set to true adds a recurse parameter") {
    val service = stubService(getBuf)

    await(KvApiV1(service).get("/path/to/key", recurse = true))
    assert(lastUri.contains("recurse"))
  }

  test("get with recurse set to false doesn't add recurse parameter") {
    val service = stubService(getBuf)

    await(KvApiV1(service).get("/path/to/key", recurse = false))
    assert(!lastUri.contains("recurse"))
  }

  test("get reports wrong paths with an error") {
    val failureService = Service.mk[Request, Response] { req =>
      val rsp = Response()
      rsp.headerMap.set("X-Consul-Index", "42")
      rsp.setStatusCode(404)
      lastUri = req.uri
      Future.value(rsp)
    }
    assertThrows[NotFound](
      await(KvApiV1(failureService).get("/wrong/path"))
    )
  }

  test("put returns true on success") {
    val service = stubService(putOkBuf)

    val result = await(KvApiV1(service).put("/path/to/key", "foobar"))
    assert(result)
  }

  test("put returns false on failure") {
    val service = stubService(putFailBuf)

    val result = await(KvApiV1(service).put("/path/to/key", "foobar"))
    assert(!result)
  }

  test("put cas flag") {
    val service = stubService(putFailBuf)

    await(KvApiV1(service).put("/path/to/key", "foobar", cas = Some("0")))
    assert(lastUri.contains(s"cas=0"))

    await(KvApiV1(service).put("/path/to/key", "foobar"))
    assert(!lastUri.contains(s"cas"))
  }

  test("delete returns true on success") {
    val service = stubService(deleteOkBuf)

    val result = await(KvApiV1(service).delete("/path/to/key"))
    assert(result)
  }

  test("delete returns false on failure") {
    val service = stubService(putFailBuf)

    val result = await(KvApiV1(service).delete("/path/to/key"))
    assert(!result)
  }

  test("delete cas flag") {
    val service = stubService(deleteFailBuf)

    await(KvApiV1(service).delete("/path/to/key", cas = Some("0")))
    assert(lastUri.contains(s"cas=0"))

    await(KvApiV1(service).delete("/path/to/key"))
    assert(!lastUri.contains(s"cas"))
  }

  test("delete by default is non-recurse") {
    val service = stubService(deleteOkBuf)

    await(KvApiV1(service).delete("/path/to/key"))
    assert(!lastUri.contains("recurse"))
  }

  test("delete with recurse set to true adds a recurse parameter") {
    val service = stubService(deleteOkBuf)

    await(KvApiV1(service).delete("/path/to/key", recurse = true))
    assert(lastUri.contains("recurse"))
  }

  test("delete with recurse set to false doesn't add recurse parameter") {
    val service = stubService(deleteOkBuf)

    await(KvApiV1(service).delete("/path/to/key", recurse = false))
    assert(!lastUri.contains("recurse"))
  }

  test("blocking index returned from one call can be used to set index on subsequent calls") {
    val service = stubService(getBuf)
    val index = await(KvApiV1(service).get("/some/path")).index.get

    await(KvApiV1(service).get("/some/path", blockingIndex = Some(index)))
    assert(lastUri.contains(s"index=$index"))
  }

  test("makes infinite retry attempts on retry = true") {
    var requestCount = 0
    val failureService = Service.mk[Request, Response] { req =>
      requestCount = requestCount + 1
      if (requestCount > 1) {
        val rsp = Response()
        rsp.setContentTypeJson()
        rsp.headerMap.set("X-Consul-Index", "4")
        rsp.content = getBuf
        Future.value(rsp)
      } else {
        Future.exception(new Exception("I have no idea who to talk to"))
      }
    }
    val result = await(KvApiV1(failureService).get("/some/path/", retry = true))
    assert(result.index == Some("4"))
    assert(result.value.head.decoded == Some("foobar"))
  }

  test("reports invalid datacenter as an unexpected response") {
    val failureService = Service.mk[Request, Response] { req =>
      val rsp = Response()
      rsp.content = Buf.Utf8("No path to datacenter")
      rsp.headerMap.set("X-Consul-Index", "0")
      rsp.setStatusCode(500) //weird that they return 500 for this
      lastUri = req.uri
      Future.value(rsp)
    }

    assertThrows[UnexpectedResponse](
      await(KvApiV1(failureService).get("/some/path/", datacenter = Some("non-existent dc")))
    )
  }

  test("get reports permission errors") {
    val failureService = Service.mk[Request, Response] { req =>
      val rsp = Response()
      rsp.setStatusCode(403)
      lastUri = req.uri
      Future.value(rsp)
    }
    assertThrows[Forbidden](
      await(KvApiV1(failureService).get("/some/path"))
    )
  }
}
