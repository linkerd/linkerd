package io.buoyant.namerd.storage.consul

import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.finagle.{Backoff, Dtab, Path, Service}
import com.twitter.io.Buf
import com.twitter.util._
import io.buoyant.consul.v1.KvApi
import io.buoyant.namerd.DtabStore.{DtabNamespaceAlreadyExistsException, DtabNamespaceInvalidException}
import io.buoyant.namerd.{Ns, VersionedDtab}
import io.buoyant.test.{ActivityValues, Awaits, Exceptions, FunSuite}

class ConsulDtabStoreTest extends FunSuite with Awaits with Exceptions with ActivityValues {

  val namespacesJson = """["namerd/dtabs/foo", "namerd/dtabs/bar"]"""
  val namespacesWithDirsJson = """["namerd/dtabs/", "namerd/dtabs/foo", "namerd/dtabs/bar/"]"""
  val namerdPrefix = "/namerd/dtabs"
  val constBackoff = Backoff.const(Duration.Zero)

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
      KvApi(service, constBackoff),
      Path.read(namerdPrefix),
      None,
      readConsistency = None,
      writeConsistency = None,
      "handler/"
    )

    @volatile var state: Activity.State[Set[Ns]] = Activity.Pending
    store.list.states respond {
      state = _
    }
    assert(state == Activity.Ok(Set("foo", "bar")))
  }

  test("List available namespaces - when Consul KV dirs are explicit") {
    val service = Service.mk[Request, Response] { req =>
      val rsp = Response()
      rsp.setContentTypeJson()
      req.getParam("index", "") match {
        case "" =>
          rsp.headerMap.set("X-Consul-Index", "4")
          rsp.content = Buf.Utf8(namespacesWithDirsJson)
          Future.value(rsp)
        case _ => Future.never

      }
    }

    val store = new ConsulDtabStore(
      KvApi(service, constBackoff),
      Path.read(namerdPrefix),
      None,
      readConsistency = None,
      writeConsistency = None,
      "handler/"
    )

    @volatile var state: Activity.State[Set[Ns]] = Activity.Pending
    store.list.states respond {
      state = _
    }
    assert(state == Activity.Ok(Set("foo")))
  }

  test("return an empty set when namespaces are absent") {
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
      KvApi(service, constBackoff),
      Path.read(namerdPrefix),
      None,
      readConsistency = None,
      writeConsistency = None,
      "handler/"
    )

    @volatile var state: Activity.State[Set[Ns]] = Activity.Pending
    store.list.states respond { state = _ }
    assert(state == Activity.Ok(Set()))
  }

  test("Throw DtabNamespaceInvalidException when creating dtab with bad namespace") {
    val service = Service.mk[Request, Response] { req =>
      val rsp = Response()
      rsp.setContentTypeJson()
      Future.never
    }
    val store = new ConsulDtabStore(
      KvApi(service, constBackoff),
      Path.read(namerdPrefix),
      None,
      readConsistency = None,
      writeConsistency = None,
      "handler/"
    )
    assertThrows[DtabNamespaceInvalidException] {
      await(store.create("%2e%2e", Dtab.empty))
    }
  }

  test("Throw DtabNamespaceAlreadyExistsException when creating an already existing dtab") {
    val service = Service.mk[Request, Response] { req =>
      val rsp = Response()
      rsp.setContentTypeJson()
      rsp.content = Buf.Utf8("""false""")
      rsp.headerMap.set("X-Consul-Index", "4")
      if (req.path.contains("default")) {
        Future.value(rsp)
      } else {
        Future.never
      }
    }
    val store = new ConsulDtabStore(
      KvApi(service, constBackoff),
      Path.read(namerdPrefix),
      None,
      readConsistency = None,
      writeConsistency = None,
      "handler/"
    )
    assertThrows[DtabNamespaceAlreadyExistsException] {
      await(store.create("default", Dtab.empty))
    }
  }

  test("Throw DtabNamespaceInvalidException when deleting dtab with bad namespace") {
    val badNamespace = "b@dn@m35p@c3"
    val service = Service.mk[Request, Response] { req =>
      val rsp = Response()
      rsp.setContentTypeJson()
      rsp.content = Buf.Utf8("""true""")
      rsp.headerMap.set("X-Consul-Index", "4")
      if (req.path.contains(badNamespace)) {
        Future.value(rsp)
      } else {
        Future.never
      }
    }
    val store = new ConsulDtabStore(
      KvApi(service, constBackoff),
      Path.read(namerdPrefix),
      None,
      readConsistency = None,
      writeConsistency = None,
      "handler/"
    )
    assertThrows[DtabNamespaceInvalidException] {
      await(store.delete(badNamespace))
    }
  }

  test("Throw DtabNamespaceInvalidException when updating dtab with bad namespace") {
    val badNamespace = "b@dn@m35p@c3"
    val service = Service.mk[Request, Response] { req =>
      val rsp = Response()
      rsp.setContentTypeJson()
      rsp.content = Buf.Utf8("""true""")
      rsp.headerMap.set("X-Consul-Index", "4")
      if (req.path.contains(badNamespace)) {
        Future.value(rsp)
      } else {
        Future.never
      }
    }
    val store = new ConsulDtabStore(
      KvApi(service, constBackoff),
      Path.read(namerdPrefix),
      None,
      readConsistency = None,
      writeConsistency = None,
      "handler/"
    )
    assertThrows[DtabNamespaceInvalidException] {
      await(store.update(badNamespace, Dtab.empty, Buf.Empty))
    }
  }

  test("Throw DtabNamespaceInvalidException when updating a dtab using put with bad namespace") {
    val badNamespace = "b@dn@m35p@c3"
    val service = Service.mk[Request, Response] { req =>
      val rsp = Response()
      rsp.setContentTypeJson()
      rsp.content = Buf.Utf8("""true""")
      rsp.headerMap.set("X-Consul-Index", "4")
      if (req.path.contains(badNamespace)) {
        Future.value(rsp)
      } else {
        Future.never
      }
    }
    val store = new ConsulDtabStore(
      KvApi(service, constBackoff),
      Path.read(namerdPrefix),
      None,
      readConsistency = None,
      writeConsistency = None,
      "handler/"
    )
    assertThrows[DtabNamespaceInvalidException] {
      await(store.put(badNamespace, Dtab.empty))
    }
  }

  test("return Activity Failed with DtabNamespaceInvalidException") {
    val badNamespace = "%2e%2e"
    val service = Service.mk[Request, Response] { req =>
      val rsp = Response()
      rsp.setContentTypeJson()
      Future.never
    }
    val store = new ConsulDtabStore(
      KvApi(service, constBackoff),
      Path.read(namerdPrefix),
      None,
      readConsistency = None,
      writeConsistency = None,
      "handler/"
    )
    @volatile var state: Activity.State[Option[VersionedDtab]] = Activity.Pending
    store.observe(badNamespace).states respond { state = _ }
    assert(state.isInstanceOf[Activity.Failed])
  }

  test("return Activity of Option[VersionedDtab] on observe") {
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
    val store = new ConsulDtabStore(
      KvApi(service, constBackoff),
      Path.read(namerdPrefix),
      None,
      readConsistency = None,
      writeConsistency = None,
      "handler/"
    )
    @volatile var state: Activity.State[Option[VersionedDtab]] = Activity.Pending
    store.observe(namespace).states respond { state = _ }
    assert(state == Activity.Ok(Some(VersionedDtab(Dtab.read(dtab), Buf.Utf8(version)))))
  }

  test("return Activity.Failed when the dtab is malformed") {
    val namespace = "default"
    val dtab = """lol im an invalid dtab"""
    val expectedException = Try(Dtab.read(dtab)) match {
      case Return(_) => fail("parsing invalid dtab should have succeeded")
      case Throw(e) => e
    }
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
    val store = new ConsulDtabStore(
      KvApi(service, constBackoff),
      Path.read(namerdPrefix),
      None,
      readConsistency = None,
      writeConsistency = None,
      "handler/"
    )
    @volatile var state: Activity.State[Option[VersionedDtab]] = Activity.Pending
    store.observe(namespace).states respond { state = _ }
    assert(state.failed.getMessage == expectedException.getMessage)
  }

}
