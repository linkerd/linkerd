package io.buoyant.consul.v1

import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.{Failure, FailureFlags, Service}
import com.twitter.io.Buf
import com.twitter.util.{Duration, Future}
import io.buoyant.test.{Awaits, Exceptions}
import org.scalatest.FunSuite

class KvApiTest extends FunSuite with Awaits with Exceptions {
  val listBuf = Buf.Utf8("""["foo/bar/", "foo/baz/"]""")
  val getBuf = Buf.Utf8("""foobar""")
  val getBufGzip = Buf.Utf8("""H4sIAAAAAAAAAEvLz09KLAIAlR/2ngYAAAA=""")
  val multiGetBuf = Buf.Utf8("""[{"LockIndex":0,"Key":"sample","Flags":0,"Value":"Zm9vYmFy","CreateIndex":10,"ModifyIndex":12}]""")
  val multiGetBufGzip = Buf.Utf8("""[{"LockIndex":0,"Key":"sample","Flags":0,"Value":"H4sIAAAAAAAA/4vKtSyLzHWrBAAIZ7JnCAAAAA==","CreateIndex":10,"ModifyIndex":12}]""")
  val putOkBuf = Buf.Utf8("""true""")
  val putFailBuf = Buf.Utf8("""false""")
  val deleteOkBuf = Buf.Utf8("""true""")
  val deleteFailBuf = Buf.Utf8("""false""")
  var lastUri = ""
  val constBackoff = Backoff.const(Duration.Zero)

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

    val result = await(KvApi(service, constBackoff).list("/foo/")())

    assert(result.index == Some("4"))
    assert(result.value.size == 2)
    assert(result.value == List("foo/bar/", "foo/baz/"))
  }

  test("list reports wrong paths with an error") {
    val failureService = Service.mk[Request, Response] { req =>
      val rsp = Response()
      rsp.headerMap.set("X-Consul-Index", "42")
      rsp.statusCode = 404
      lastUri = req.uri
      Future.value(rsp)
    }
    assertThrows[NotFound](
      await(KvApi(failureService, constBackoff).list("/wrong/path/")())
    )
  }

  test("list supports consistency parameter") {
    val service = stubService(listBuf)
    val api = KvApi(service, constBackoff)

    await(api.list("/foo/")())
    assert(!lastUri.contains("consistent"))
    assert(!lastUri.contains("stale"))

    await(api.list("/foo/", consistency = Some(ConsistencyMode.Default))())
    assert(!lastUri.contains("consistent"))
    assert(!lastUri.contains("stale"))

    await(api.list("/foo/", consistency = Some(ConsistencyMode.Stale))())
    assert(lastUri.contains("stale=true"))

    await(api.list("/foo/", consistency = Some(ConsistencyMode.Consistent))())
    assert(lastUri.contains("consistent=true"))
  }

  test("get returns an indexed value") {
    val service = stubService(getBuf)

    val result = await(KvApi(service, constBackoff).get("/some/path/to/key")())
    assert(result.index == Some("4"))
    assert(result.value == "foobar")
  }

  test("get returns an indexed value (Gzip)") {
    val service = stubService(getBufGzip)

    val result = await(KvApi(service, constBackoff, enableValueCompression = true).get("/some/path/to/key")())
    assert(result.index == Some("4"))
    assert(result.value == "foobar")
  }

  test("get uses raw values") {
    val service = stubService(putFailBuf)

    await(KvApi(service, constBackoff).get("/path/to/key")())
    assert(lastUri.contains(s"raw=true"))
  }

  test("get reports wrong paths with an error") {
    val failureService = Service.mk[Request, Response] { req =>
      val rsp = Response()
      rsp.headerMap.set("X-Consul-Index", "42")
      rsp.statusCode = 404
      lastUri = req.uri
      Future.value(rsp)
    }
    assertThrows[NotFound](
      await(KvApi(failureService, constBackoff).get("/wrong/path")())
    )
  }

  test("get supports consistency parameter") {
    val service = stubService(getBuf)
    val api = KvApi(service, constBackoff)

    await(api.get("/path/to/key")())
    assert(!lastUri.contains("consistent"))
    assert(!lastUri.contains("stale"))

    await(api.get("/path/to/key", consistency = Some(ConsistencyMode.Default))())
    assert(!lastUri.contains("consistent"))
    assert(!lastUri.contains("stale"))

    await(api.get("/path/to/key", consistency = Some(ConsistencyMode.Stale))())
    assert(lastUri.contains("stale=true"))

    await(api.get("/path/to/key", consistency = Some(ConsistencyMode.Consistent))())
    assert(lastUri.contains("consistent=true"))
  }

  test("multiGet returns an indexed seq of values") {
    val service = stubService(multiGetBuf)

    val result = await(KvApi(service, constBackoff).multiGet("/sample")())
    assert(result.index == Some("4"))
    assert(result.value.size == 1)
    assert(result.value.head.decoded == Some("foobar"))
  }

  test("multiGet returns an indexed seq of values (Gzip)") {
    val service = stubService(multiGetBufGzip)

    val result = await(KvApi(service, constBackoff, enableValueCompression = true).multiGet("/sample")())
    assert(result.index == Some("4"))
    assert(result.value.size == 1)
    assert(result.value.head.decoded == Some("foobar"))
  }


  test("multiGet by default is non-recurse") {
    val service = stubService(multiGetBuf)

    await(KvApi(service, constBackoff).multiGet("/path/to/key")())
    assert(!lastUri.contains("recurse"))
  }

  test("multiGet with recurse set to true adds a recurse parameter") {
    val service = stubService(multiGetBuf)

    await(KvApi(service, constBackoff).multiGet("/path/to/key", recurse = Some(true))())
    assert(lastUri.contains("recurse"))
  }

  test("multiGet reports wrong paths with an error") {
    val failureService = Service.mk[Request, Response] { req =>
      val rsp = Response()
      rsp.headerMap.set("X-Consul-Index", "42")
      rsp.statusCode = 404
      lastUri = req.uri
      Future.value(rsp)
    }
    assertThrows[NotFound](
      await(KvApi(failureService, constBackoff).multiGet("/wrong/path")())
    )
  }

  test("multiGet supports consistency parameter") {
    val service = stubService(multiGetBuf)
    val api = KvApi(service, constBackoff)

    await(api.multiGet("/path/to/key")())
    assert(!lastUri.contains("consistent"))
    assert(!lastUri.contains("stale"))

    await(api.multiGet("/path/to/key", consistency = Some(ConsistencyMode.Default))())
    assert(!lastUri.contains("consistent"))
    assert(!lastUri.contains("stale"))

    await(api.multiGet("/path/to/key", consistency = Some(ConsistencyMode.Stale))())
    assert(lastUri.contains("stale=true"))

    await(api.multiGet("/path/to/key", consistency = Some(ConsistencyMode.Consistent))())
    assert(lastUri.contains("consistent=true"))
  }

  test("put returns true on success") {
    val service = stubService(putOkBuf)

    val result = await(KvApi(service, constBackoff).put("/path/to/key", "foobar")())
    assert(result)
  }

  test("put returns false on failure") {
    val service = stubService(putFailBuf)

    val result = await(KvApi(service, constBackoff).put("/path/to/key", "foobar")())
    assert(!result)
  }


  test("put compresses value when enableValueCompression: true") {
    val service = Service.mk[Request, Response] { req =>
      assert(req.contentString == "H4sIAAAAAAAAAEvLz09KLAIAlR/2ngYAAAA=")
      val rsp = Response()
      rsp.content(Buf.Utf8("""true"""))
      rsp.setContentTypeJson()
      rsp.headerMap.set("X-Consul-Index", "4")
      Future.value(rsp)
    }

    val result = await(KvApi(service, constBackoff, enableValueCompression = true).put("/path/to/key", "foobar")())
    assert(result)
  }

  test("put cas flag") {
    val service = stubService(putFailBuf)

    await(KvApi(service, constBackoff).put("/path/to/key", "foobar", cas = Some("0"))())
    assert(lastUri.contains(s"cas=0"))

    await(KvApi(service, constBackoff).put("/path/to/key", "foobar")())
    assert(!lastUri.contains(s"cas"))
  }

  test("put supports consistency parameter") {
    val service = stubService(putOkBuf)
    val api = KvApi(service, constBackoff)

    await(api.put("/path/to/key", "foobar")())
    assert(!lastUri.contains("consistent"))
    assert(!lastUri.contains("stale"))

    await(api.put("/path/to/key", "foobar", consistency = Some(ConsistencyMode.Default))())
    assert(!lastUri.contains("consistent"))
    assert(!lastUri.contains("stale"))

    await(api.put("/path/to/key", "foobar", consistency = Some(ConsistencyMode.Stale))())
    assert(lastUri.contains("stale=true"))

    await(api.put("/path/to/key", "foobar", consistency = Some(ConsistencyMode.Consistent))())
    assert(lastUri.contains("consistent=true"))
  }

  test("delete returns true on success") {
    val service = stubService(putOkBuf)

    val result = await(KvApi(service, constBackoff).delete("/path/to/key")())
    assert(result)
  }

  test("delete returns false on failure") {
    val service = stubService(putFailBuf)

    val result = await(KvApi(service, constBackoff).delete("/path/to/key")())
    assert(!result)
  }

  test("delete cas flag") {
    val service = stubService(putFailBuf)

    await(KvApi(service, constBackoff).delete("/path/to/key", cas = Some("0"))())
    assert(lastUri.contains(s"cas=0"))

    await(KvApi(service, constBackoff).delete("/path/to/key")())
    assert(!lastUri.contains(s"cas"))
  }

  test("delete by default is non-recurse") {
    val service = stubService(deleteOkBuf)

    await(KvApi(service, constBackoff).delete("/path/to/key")())
    assert(!lastUri.contains("recurse"))
  }

  test("delete with recurse set to true adds a recurse parameter") {
    val service = stubService(deleteOkBuf)

    await(KvApi(service, constBackoff).delete("/path/to/key", recurse = Some(true))())
    assert(lastUri.contains("recurse"))
  }

  test("delete supports consistency parameter") {
    val service = stubService(putOkBuf)
    val api = KvApi(service, constBackoff)

    await(api.delete("/path/to/key")())
    assert(!lastUri.contains("consistent"))
    assert(!lastUri.contains("stale"))

    await(api.delete("/path/to/key", consistency = Some(ConsistencyMode.Default))())
    assert(!lastUri.contains("consistent"))
    assert(!lastUri.contains("stale"))

    await(api.delete("/path/to/key", consistency = Some(ConsistencyMode.Stale))())
    assert(lastUri.contains("stale=true"))

    await(api.delete("/path/to/key", consistency = Some(ConsistencyMode.Consistent))())
    assert(lastUri.contains("consistent=true"))
  }

  test("blocking index returned from one call can be used to set index on subsequent calls") {
    val service = stubService(getBuf)
    val index = await(KvApi(service, constBackoff).get("/some/path")()).index.get

    await(KvApi(service, constBackoff).get("/some/path", blockingIndex = Some(index))())
    assert(lastUri.contains(s"index=$index"))
  }

  test("propagates client failures") {
    val failureService = Service.mk[Request, Response] { req =>
      Future.exception(new Exception("I have no idea who to talk to"))
    }
    assertThrows[Exception](
      await(KvApi(failureService, constBackoff).list("/foo/")())
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
    val result = await(KvApi(failureService, constBackoff).list("/some/path/", retry = true)())
    assert(result.index == Some("4"))
    assert(result.value == List("foo/bar/", "foo/baz/"))
  }

  test("doesn't retry on Failure.Interrupted with retry = true") {
    val failureService = Service.mk[Request, Response] { _ =>
      Future.exception(
        Failure("consul observation released", FailureFlags.Interrupted)
      )
    }
    val api = KvApi(failureService, constBackoff)
    val result = api.list("/some/path/", retry = true)()
    assertThrows[Failure](
      await(result)
    )
  }

  test("reports invalid datacenter as an unexpected response") {
    val failureService = Service.mk[Request, Response] { req =>
      val rsp = Response()
      rsp.content = Buf.Utf8("No path to datacenter")
      rsp.headerMap.set("X-Consul-Index", "0")
      rsp.statusCode = 500 //weird that they return 500 for this
      lastUri = req.uri
      Future.value(rsp)
    }

    assertThrows[UnexpectedResponse](
      await(KvApi(failureService, constBackoff).list("/some/path/", datacenter = Some("non-existent dc"))())
    )
  }

  test("get reports permission errors") {
    val failureService = Service.mk[Request, Response] { req =>
      val rsp = Response()
      rsp.statusCode = 403
      lastUri = req.uri
      Future.value(rsp)
    }
    assertThrows[Forbidden](
      await(KvApi(failureService, constBackoff).get("/some/path")())
    )
  }
}
