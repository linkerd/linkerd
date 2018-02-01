package io.buoyant.linkerd.protocol.h2

import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.buoyant.h2._
import com.twitter.finagle.http.{Request => H1Request, Response => H1Response}
import com.twitter.finagle.{Dtab, Path, Service}
import com.twitter.io.Buf
import com.twitter.util.Future
import io.buoyant.router.RoutingFactory._
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class IngressIdentifierTest extends FunSuite with Awaits {

  val ingressListResource = Buf.Utf8("""{
    "kind":"IngressList",
    "apiVersion":"extensions/v1beta",
    "items": [{
      "kind":"Ingress",
      "apiVersion":"extensions/v1beta",
      "metadata":{"name":"test-ingress","namespace":"fooNamespace","selfLink":"/apis/extensions/v1beta1/namespaces/srv/ingresses/test-ingress","resourceVersion":"4430527"},
      "spec": {
        "backend": {
          "serviceName": "defaultService",
          "servicePort": "defaultPort"
        },
        "rules": [{
          "host": "foo.bar.com",
          "http": {
            "paths": [{
              "path": "/fooPath/.*",
              "backend": {
                "serviceName": "fooPathService",
                "servicePort": "fooPathPort"
              }
            },
            {
              "backend": {
                "serviceName": "fooHostService",
                "servicePort": "fooHostPort"
              }
            }]
          }
        }]
      }
    }]
  }""")

  val service = Service.mk[H1Request, H1Response] {
    case req if req.uri.contains("/apis/extensions/v1beta1/ingresses") =>
      val rsp = H1Response()
      rsp.content = ingressListResource
      Future.value(rsp)
    case req if req.uri.contains("/apis/extensions/v1beta1/watch/ingresses") =>
      val rsp = H1Response()
      rsp.content = ingressListResource
      Future.value(rsp)
    case req =>
      fail(s"unexpected request: $req")
  }

  test("identifies requests by host, without path") {
    val identifier = new IngressIdentifier(Path.Utf8("svc"), () => Dtab.empty, None, service, "linkerd", false)
    val req0 = Request("http", Method.Get, "foo.bar.com", "/penguins", Stream.empty())
    await(identifier(req0)) match {
      case IdentifiedRequest(Dst.Path(name, base, local), req1) =>
        assert(name == Path.read("/svc/fooNamespace/fooHostPort/fooHostService"))
      case id: UnidentifiedRequest[Request] => fail(s"unexpected identification: ${id.reason}")
    }
  }

  test("identifies requests by host & path") {
    val identifier = new IngressIdentifier(Path.Utf8("svc"), () => Dtab.empty, None, service, "linkerd", false)
    val req0 = Request("http", Method.Get, "foo.bar.com", "/fooPath/penguins", Stream.empty())
    await(identifier(req0)) match {
      case IdentifiedRequest(Dst.Path(name, base, local), req1) =>
        assert(name == Path.read("/svc/fooNamespace/fooPathPort/fooPathService"))
      case id: UnidentifiedRequest[Request] => fail(s"unexpected identification: ${id.reason}")
    }
  }

  test("falls back to the default backend") {
    val identifier = new IngressIdentifier(Path.Utf8("svc"), () => Dtab.empty, None, service, "linkerd", false)
    val req0 = Request("http", Method.Get, "authority", "/", Stream.empty())
    await(identifier(req0)) match {
      case IdentifiedRequest(Dst.Path(name, base, local), req1) =>
        assert(name == Path.read("/svc/fooNamespace/defaultPort/defaultService"))
      case id: UnidentifiedRequest[Request] => fail(s"unexpected identification: ${id.reason}")
    }
  }

  test("ignoreDefaultBackends ingress identifies requests by host & path") {
    val identifier = new IngressIdentifier(Path.Utf8("svc"), () => Dtab.empty, None, service, "linkerd", true)
    val req0 = Request("http", Method.Get, "foo.bar.com", "/fooPath/penguins", Stream.empty())
    await(identifier(req0)) match {
      case IdentifiedRequest(Dst.Path(name, base, local), req1) =>
        assert(name == Path.read("/svc/fooNamespace/fooPathPort/fooPathService"))
      case id: UnidentifiedRequest[Request] => fail(s"unexpected identification: ${id.reason}")
    }
  }

  test("ignoreDefaultBackends ingress does not fall back to the default backend") {
    val identifier = new IngressIdentifier(Path.Utf8("svc"), () => Dtab.empty, None, service, "linkerd", true)
    val req0 = Request("http", Method.Get, "authority", "/fooPath/puffins", Stream.empty())
    await(identifier(req0)) match {
      case IdentifiedRequest(Dst.Path(name, base, local), req1) =>
        fail(s"unexpected identification: ${name}")
      case id: UnidentifiedRequest[Request] => assert(id.reason == "no ingress rule matches")
    }
  }
}
