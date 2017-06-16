package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.{Dtab, Path, Service}
import com.twitter.io.Buf
import com.twitter.util.Future
import io.buoyant.router.RoutingFactory._
import io.buoyant.test.Awaits
import org.scalatest.FunSuite
import com.twitter.finagle.http.{Request, RequestBuilder, Response}

class IngressIdentifierTest extends FunSuite with Awaits {

  val ingressListResource = Buf.Utf8("""{
    "kind":"IngressList",
    "apiVersion":"extensions/v1beta",
    "items": [{
      "kind":"Ingress",
      "apiVersion":"extensions/v1beta",
      "metadata": {
        "name":"test-ingress",
        "namespace":"fooNamespace",
        "selfLink":"/apis/extensions/v1beta1/namespaces/srv/ingresses/test-ingress",
        "resourceVersion":"4430527",
        "annotations": {
          "linkerd.io/use-capturing-groups": "true"
        }
      },
      "spec": {
        "backend": {
          "serviceName": "defaultService",
          "servicePort": "defaultPort"
        },
        "rules": [{
          "host": "foo.bar.com",
          "http": {
            "paths": [{
              "path": "(/?fooPath)(/?.*)",
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
        },{
          "http": {
            "paths": [{
              "path": "/barPath(/?.*)",
              "backend": {
                "serviceName": "barPathService",
                "servicePort": "barPathPort"
              }
            }]
          }
        }]
      }
    }]
  }""")

  val service = Service.mk[Request, Response] {
    case req if req.uri == "/apis/extensions/v1beta1/ingresses" =>
      val rsp = Response()
      rsp.content = ingressListResource
      Future.value(rsp)
    case req if req.uri == "/apis/extensions/v1beta1/ingresses?watch=true" =>
      val rsp = Response()
      rsp.content = ingressListResource
      Future.value(rsp)
    case req =>
      fail(s"unexpected request: $req")
  }

  test("identifies requests by host, without path") {
    val identifier = new IngressIdentifier(Path.Utf8("svc"), () => Dtab.empty, None, false, service)
    val req0 = request("/penguins", "foo.bar.com")
    await(identifier(req0)) match {
      case IdentifiedRequest(Dst.Path(name, base, local), req1) =>
        assert(name == Path.read("/svc/fooNamespace/fooHostPort/fooHostService"))
        assert(req1.path == "/penguins")
      case id: UnidentifiedRequest[Request] => fail(s"unexpected identification: ${id.reason}")
    }
  }

  test("identifies requests by host & path") {
    val identifier = new IngressIdentifier(Path.Utf8("svc"), () => Dtab.empty, None, false, service)
    val req0 = request("/fooPath/penguins", "foo.bar.com")
    await(identifier(req0)) match {
      case IdentifiedRequest(Dst.Path(name, _, _), req1) =>
        assert(name == Path.read("/svc/fooNamespace/fooPathPort/fooPathService"))
        assert(req1.path == "/fooPath/penguins")
      case id: UnidentifiedRequest[Request] => fail(s"unexpected identification: ${id.reason}")
    }
  }

  test("falls back to the default backend") {
    val identifier = new IngressIdentifier(Path.Utf8("svc"), () => Dtab.empty, None, false, service)
    val req0 = request("/")
    await(identifier(req0)) match {
      case IdentifiedRequest(Dst.Path(name, _, _), req1) =>
        assert(name == Path.read("/svc/fooNamespace/defaultPort/defaultService"))
        assert(req1.path == "/")
      case id: UnidentifiedRequest[Request] => fail(s"unexpected identification: ${id.reason}")
    }
  }

  test("identifies requests by path and rewrites it using capturing groups") {
    val identifier = new IngressIdentifier(Path.Utf8("svc"), () => Dtab.empty, None, true, service)
    val req0 = request("/barPath/penguins")
    await(identifier(req0)) match {
      case IdentifiedRequest(Dst.Path(name, _, _), req1) =>
        assert(name == Path.read("/svc/fooNamespace/barPathPort/barPathService"))
        assert(req1.path == "/penguins")
      case id: UnidentifiedRequest[Request] => fail(s"unexpected identification: ${id.reason}")
    }
  }

  def request(uri: String, host: String = ""): Request = (uri, host) match {
    case (_, "") => RequestBuilder.create().url(s"http:///${uri.stripPrefix("/")}").buildGet()
    case (_, _) => RequestBuilder.create().url(s"http://$host/${uri.stripPrefix("/")}").addHeader("Host", host).buildGet()
  }
}

