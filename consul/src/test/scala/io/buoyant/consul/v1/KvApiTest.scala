package io.buoyant.consul.v1

import com.twitter.conversions.time._
import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response}
import com.twitter.io.Buf
import com.twitter.util.Future
import io.buoyant.test.{Awaits, Exceptions}
import org.scalatest.FunSuite

class KvApiTest extends FunSuite with Awaits with Exceptions {
  val listBuf = Buf.Utf8("""["foo/bar/", "foo/baz/"]""")
  val getBuf = Buf.Utf8("""foobar""")
  val putOkBuf = Buf.Utf8("""true""")
  val putFailBuf = Buf.Utf8("""false""")
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

  test("list returns an indexed seq of key names") {
    val service = stubService(listBuf)

    val result = await(KvApi(service).list("/foo/"))

    assert(result.index == Some("4"))
    assert(result.value.size == 2)
    assert(result.value == List("foo/bar/", "foo/baz/"))
  }

  test("list reports wrong paths with an error") {
    val failureService = Service.mk[Request, Response] { req =>
      val rsp = Response()
      rsp.headerMap.set("X-Consul-Index", "42")
      rsp.setStatusCode(404)
      lastUri = req.uri
      Future.value(rsp)
    }
    assertThrows[NotFound](
      await(KvApi(failureService).list("/wrong/path/"))
    )
  }

  test("get returns an indexed value") {
    val service = stubService(getBuf)

    val result = await(KvApi(service).get("/some/path/to/key"))
    assert(result.index == Some("4"))
    assert(result.value == "foobar")
  }

  test("get uses raw values") {
    val service = stubService(putFailBuf)

    await(KvApi(service).get("/path/to/key"))
    assert(lastUri.contains(s"raw=true"))
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
      await(KvApi(failureService).get("/wrong/path"))
    )
  }

  test("put returns true on success") {
    val service = stubService(putOkBuf)

    val result = await(KvApi(service).put("/path/to/key", "foobar"))
    assert(result)
  }

  test("put returns false on failure") {
    val service = stubService(putFailBuf)

    val result = await(KvApi(service).put("/path/to/key", "foobar"))
    assert(!result)
  }

  test("put cas flag") {
    val service = stubService(putFailBuf)

    await(KvApi(service).put("/path/to/key", "foobar", cas = Some("0")))
    assert(lastUri.contains(s"cas=0"))

    await(KvApi(service).put("/path/to/key", "foobar"))
    assert(!lastUri.contains(s"cas"))
  }

  test("delete returns true on success") {
    val service = stubService(putOkBuf)

    val result = await(KvApi(service).delete("/path/to/key"))
    assert(result)
  }

  test("delete returns false on failure") {
    val service = stubService(putFailBuf)

    val result = await(KvApi(service).delete("/path/to/key"))
    assert(!result)
  }

  test("delete cas flag") {
    val service = stubService(putFailBuf)

    await(KvApi(service).delete("/path/to/key", cas = Some("0")))
    assert(lastUri.contains(s"cas=0"))

    await(KvApi(service).delete("/path/to/key"))
    assert(!lastUri.contains(s"cas"))
  }

  test("blocking index returned from one call can be used to set index on subsequent calls") {
    val service = stubService(getBuf)
    val index = await(KvApi(service).get("/some/path")).index.get

    await(KvApi(service).get("/some/path", blockingIndex = Some(index)))
    assert(lastUri.contains(s"index=$index"))
  }

  test("propagates client failures") {
    val failureService = Service.mk[Request, Response] { req =>
      Future.exception(new Exception("I have no idea who to talk to"))
    }
    assertThrows[Exception](
      await(KvApi(failureService).list("/foo/"))
    )
  }

  test("makes infinite retry attempts on retry = true") {
    var requestCount = 0
    val failureService = Service.mk[Request, Response] { req =>
      requestCount = requestCount + 1
      if (requestCount > 1) {
        val rsp = Response()
        rsp.setContentTypeJson()
        rsp.headerMap.set("X-Consul-Index", "4")
        rsp.content = listBuf
        Future.value(rsp)
      } else {
        Future.exception(new Exception("I have no idea who to talk to"))
      }
    }
    val result = await(KvApi(failureService).list("/some/path/", retry = true))
    assert(result.index == Some("4"))
    assert(result.value == List("foo/bar/", "foo/baz/"))
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
      await(KvApi(failureService).list("/some/path/", datacenter = Some("non-existent dc")))
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
      await(KvApi(failureService).get("/some/path"))
    )
  }
}
