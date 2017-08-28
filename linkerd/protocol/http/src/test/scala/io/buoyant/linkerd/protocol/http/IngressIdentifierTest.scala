package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.http.{Method, Request, Response}
import com.twitter.finagle.{Dtab, Path, Service}
import com.twitter.io.Buf
import com.twitter.util.Future
import io.buoyant.router.RoutingFactory.{IdentifiedRequest, UnidentifiedRequest}
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class IngressIdentifierTest extends FunSuite with Awaits {

  val ingressListResource = Buf.Utf8(
    """{
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
  }"""
  )

  val service = Service.mk[Request, Response] {
    case req if req.uri.contains("/apis/extensions/v1beta1/ingresses") =>
      val rsp = Response()
      rsp.content = ingressListResource
      Future.value(rsp)
    case req =>
      fail(s"unexpected request: $req")
  }

  test("identifies requests by host, without path") {
    val identifier = new IngressIdentifier(Path.Utf8("svc"), () => Dtab.empty, None, service, "linkerd")
    val req0 = Request()
    req0.method = Method.Get
    req0.uri = "/penguins"
    req0.host = "foo.bar.com"

    await(identifier(req0)) match {
      case IdentifiedRequest(Dst.Path(name, base, local), req1) =>
        assert(name == Path.read("/svc/fooNamespace/fooHostPort/fooHostService"))
      case id: UnidentifiedRequest[Request] => fail(s"unexpected identification: ${id.reason}")
    }
  }

  test("identifies requests by host & path") {
    val identifier = new IngressIdentifier(Path.Utf8("svc"), () => Dtab.empty, None, service, "linkerd")
    val req0 = Request()
    req0.method = Method.Get
    req0.uri = "/fooPath/penguins"
    req0.host = "foo.bar.com"

    await(identifier(req0)) match {
      case IdentifiedRequest(Dst.Path(name, base, local), req1) =>
        assert(name == Path.read("/svc/fooNamespace/fooPathPort/fooPathService"))
      case id: UnidentifiedRequest[Request] => fail(s"unexpected identification: ${id.reason}")
    }
  }

  test("falls back to the default backend") {
    val identifier = new IngressIdentifier(Path.Utf8("svc"), () => Dtab.empty, None, service, "linkerd")
    val req0 = Request()
    req0.method = Method.Get
    req0.uri = "/fooPath/penguins"
    req0.host = "authority"
    await(identifier(req0)) match {
      case IdentifiedRequest(Dst.Path(name, base, local), req1) =>
        assert(name == Path.read("/svc/fooNamespace/defaultPort/defaultService"))
      case id: UnidentifiedRequest[Request] => fail(s"unexpected identification: ${id.reason}")
    }
  }

}
