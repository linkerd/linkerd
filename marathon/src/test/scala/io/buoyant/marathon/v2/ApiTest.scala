package io.buoyant.marathon.v2

import com.twitter.finagle.http.{Response, Request}
import com.twitter.finagle.{Address, Service}
import com.twitter.io.Buf
import com.twitter.util.Future
import io.buoyant.test.Awaits
import java.net.InetSocketAddress
import org.scalatest.FunSuite

class ApiTest extends FunSuite with Awaits {
  val appsBuf = Buf.Utf8("""
    {
      "apps": [
        {
          "id": "/foo",
          "cmd": null,
          "labels": { "LABEL_FOO": "BAR" }
        },
        {
          "id": "/bar",
          "cmd": "bar cmd"
        },
        {
          "id": "/baz",
          "cmd": null
        }
      ]
    }
  """)

  val appBuf = Buf.Utf8("""
    {
      "app": {
        "id": "/foo",
        "cmd": "foo cmd",
        "tasks": [
          {
            "id": "booksId",
            "host": "1.2.3.4",
            "ports": [7000, 7001, 7002]
          },
          {
            "id": "booksId2",
            "host": "5.6.7.8",
            "ports": [7003, 7004, 7005]
          }
        ]
      }
    }
  """)

  val noApps = Buf.Utf8("""
    {"apps":[]}
  """)

  val appNotFoundBuf = Buf.Utf8("""
    {"message":"App '/foo' does not exist"}
  """)

  def stubService(buf: Buf) = Service.mk[Request, Response] { req =>
    val rsp = Response()
    rsp.content = buf
    Future.value(rsp)
  }

  test("getAppIds endpoint returns a seq of app names") {
    val service = stubService(appsBuf)

    val response = await(Api(service, "host", "prefix").getAppIds())
    assert(response.size == 3)
    assert(response == Set[String](
      "/foo",
      "/bar",
      "/baz"
    ))
  }

  test("getAppIds endpoint returns an empty seq when there are no apps") {
    val service = stubService(noApps)

    val response = await(Api(service, "host", "prefix").getAppIds())
    assert(response.size == 0)
  }

  test("getAddrs endpoint returns a seq of addresses") {
    val service = stubService(appBuf)

    val response = await(Api(service, "host", "prefix").getAddrs("foo"))
    assert(response.size == 2)
    assert(response == Set[Address](
      Address("1.2.3.4", 7000),
      Address("5.6.7.8", 7003)
    ))
  }

  test("getAddrs endpoint returns an empty set of addresses if app not found") {
    val service = stubService(appNotFoundBuf)

    val response = await(Api(service, "host", "prefix").getAddrs("foo"))
    assert(response.size == 0)
  }

  test("propagates client failures") {
    val failureService = Service.mk[Request, Response] { req =>
      Future.exception(new Exception("I have no idea who to talk to"))
    }
    try {
      await(Api(failureService, "host", "prefix").getAppIds())
      assert(false)
    } catch {
      case e: Exception => assert(true)
    }
  }
}
