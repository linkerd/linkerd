package io.buoyant.namerd.storage.consul

import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.finagle.{Path, Service}
import com.twitter.io.Buf
import com.twitter.util.{Activity, Future}
import io.buoyant.consul.v1.KvApi
import io.buoyant.namerd.Ns
import io.buoyant.test.{Awaits, Exceptions, FunSuite}

class ConsulDtabStoreTest extends FunSuite with Awaits with Exceptions {

  val namespacesJson = """["namerd/dtabs/foo/bar/", "namerd/dtabs/foo/baz/"]"""
  val namerdPrefix = "/namerd/dtabs"

  test("List available namespaces") {
    val service = Service.mk[Request, Response] { req =>
      val rsp = Response()
      rsp.setContentTypeJson()
      req.getParam("index", "") match {
        case "" =>
          rsp.headerMap.set("X-Consul-Index", "4")
          rsp.content = Buf.Utf8(namespacesJson)
          Future.value(rsp)
        case _ => Future.never

      }
    }

    val store = new ConsulDtabStore(
      KvApi(service),
      Path.read(namerdPrefix),
      None,
      readConsistency = None,
      writeConsistency = None
    )

    @volatile var state: Activity.State[Set[Ns]] = Activity.Pending
    store.list.states respond {
      state = _
    }
    assert(state == Activity.Ok(Set("foo/bar", "foo/baz")))
  }

  test("Block when namespaces are not found") {
    val service = Service.mk[Request, Response] { req =>
      val rsp = Response(status = Status(404))
      req.getParam("index", "") match {
        case "" =>
          rsp.headerMap.set("X-Consul-Index", "4")
          Future.value(rsp)
        case _ => Future.never
      }
    }

    val store = new ConsulDtabStore(
      KvApi(service),
      Path.read(namerdPrefix),
      None,
      readConsistency = None,
      writeConsistency = None
    )

    @volatile var state: Activity.State[Set[Ns]] = Activity.Pending
    store.list.states respond {
      state = _
    }
    assert(state == Activity.Ok(Set()))
  }
}
