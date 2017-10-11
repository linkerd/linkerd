package io.buoyant.k8s.v1beta1

import com.twitter.finagle.http._
import com.twitter.finagle.{Service => FService}
import com.twitter.io.Buf
import com.twitter.util._
import io.buoyant.test.{Awaits, Exceptions}
import org.scalatest.{FunSuite, OptionValues}

class ApiTest extends FunSuite with Awaits with Exceptions with OptionValues {

  val ingressResource = Buf.Utf8("""{
    "kind":"Ingress",
    "apiVersion":"extensions/v1beta",
    "metadata":{"name":"test-ingress","namespace":"srv","selfLink":"/apis/extensions/v1beta1/namespaces/srv/ingresses/test-ingress","uid":"1b0e7393-4d10-11e5-9859-42010af01815","resourceVersion":"4430527","creationTimestamp":"2015-08-27T23:05:27Z"},
    "spec": {
      "rules": [{
        "http": {
          "paths": [{
            "path": "/fooPath",
            "backend": {
              "serviceName": "/fooService",
              "servicePort": "/fooPort"
            }
          }]
        }
      }]
    }
  }""")

  test("get ingress") {
    @volatile var reqCount = 0
    @volatile var failure: Throwable = null
    val service = FService.mk[Request, Response] { req =>
      reqCount += 1
      reqCount match {
        case 1 =>
          try {
            assert(req.uri == s"/apis/extensions/v1beta1/namespaces/srv/ingresses/test-ingress")
            val rsp = Response()
            rsp.version = req.version
            rsp.setContentTypeJson()
            rsp.headerMap("Transfer-Encoding") = "chunked"
            rsp.writer.write(ingressResource) before rsp.writer.close()
            Future.value(rsp)
          } catch {
            case e: Throwable =>
              failure = e
              Future.exception(e)
          }

        case _ => Future.never
      }
    }

    val ns = Api(service).withNamespace("srv")
    val ingress = await(ns.ingresses.named("test-ingress").get()).value

    val paths = for (
      spec <- ingress.spec.toSeq;
      rules <- spec.rules.toSeq;
      rule <- rules;
      http <- rule.http.toSeq;
      path <- http.paths
    ) yield {
      assert(path.path.contains("/fooPath"))
      assert(path.backend.serviceName == "/fooService")
      assert(path.backend.servicePort == "/fooPort")
      path
    }

    assert(paths.size == 1)
  }
}
