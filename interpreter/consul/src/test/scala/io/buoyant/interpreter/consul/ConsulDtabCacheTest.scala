package io.buoyant.interpreter.consul

import com.twitter.finagle.{Dtab, Path, Service}
import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.finagle.service.Backoff
import com.twitter.io.Buf
import com.twitter.util.{Activity, Duration, Future}
import io.buoyant.consul.v1.KvApi
import io.buoyant.test.FunSuite

class ConsulDtabCacheTest extends FunSuite {
  val constBackoff = Backoff.const(Duration.Zero)
  val namerdPrefix = "/namerd/dtabs"

  test("return Activity of Option[Dtab] on observe") {
    val namespace = "default"
    val dtab = """/svc => /#/inet/host/port;"""
    val version = "4"
    val service = Service.mk[Request, Response] { req =>
      val rsp = Response()
      rsp.setContentTypeJson()
      rsp.headerMap.set("X-Consul-Index", version)
      rsp.content = Buf.Utf8(dtab)
      if (req.path.contains(namespace) && req.getParam("index", "").isEmpty) {
        Future.value(rsp)
      } else {
        Future.never
      }

    }
    val store = new ConsulDtabCache(
      KvApi(service, constBackoff),
      Path.read(namerdPrefix),
      None,
      readConsistency = None,
      writeConsistency = None
    )
    @volatile var state: Activity.State[Option[Dtab]] = Activity.Pending
    store.observe(namespace).states respond { state = _ }
    assert(state == Activity.Ok(Some(Dtab.read(dtab))))
  }

  test("return Activity of failed for malformed dtab") {
    val namespace = "default"
    val dtab = """/svc =>> /#/inet/host/port;"""
    val version = "4"
    val service = Service.mk[Request, Response] { req =>
      val rsp = Response()
      rsp.setContentTypeJson()
      rsp.headerMap.set("X-Consul-Index", version)
      rsp.content = Buf.Utf8(dtab)
      if (req.path.contains(namespace) && req.getParam("index", "").isEmpty) {
        Future.value(rsp)
      } else {
        Future.never
      }

    }
    val store = new ConsulDtabCache(
      KvApi(service, constBackoff),
      Path.read(namerdPrefix),
      None,
      readConsistency = None,
      writeConsistency = None
    )
    @volatile var state: Activity.State[Option[Dtab]] = Activity.Pending
    store.observe(namespace).states respond { state = _ }
    assert(state.isInstanceOf[Activity.Failed])
  }

  test("return Activity Ok on dtab not found") {
    val namespace = "default"
    val dtab = """/svc =>> /#/inet/host/port;"""
    val version = "4"
    val service = Service.mk[Request, Response] { req =>
      val rsp = Response(Status.NotFound)
      rsp.setContentTypeJson()
      rsp.headerMap.set("X-Consul-Index", version)
      if (req.path.contains(namespace) && req.getParam("index", "").isEmpty) {
        Future.value(rsp)
      } else {
        Future.never
      }

    }
    val store = new ConsulDtabCache(
      KvApi(service, constBackoff),
      Path.read(namerdPrefix),
      None,
      readConsistency = None,
      writeConsistency = None
    )
    @volatile var state: Activity.State[Option[Dtab]] = Activity.Pending
    store.observe(namespace).states respond { state = _ }
    assert(state == Activity.Ok(None))
  }
}
