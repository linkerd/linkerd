package io.buoyant.linkerd.protocol.http

import com.twitter.finagle._
import com.twitter.finagle.client.AddrMetadataExtraction.AddrMetadata
import com.twitter.finagle.http.{Status, _}
import com.twitter.util.Future
import io.buoyant.namer.Metadata
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class RewriteHostHeaderTest extends FunSuite with Awaits {

  def makeService(meta: Addr.Metadata)(transform: Request => Response) = {
    val svc = Service.mk[Request, Response] { req =>
      Future.value(transform(req))
    }

    val stk = RewriteHostHeader.module.toStack(
      Stack.Leaf(RewriteHostHeader.module.role, ServiceFactory.const(svc))
    )

    await(stk.make(Stack.Params.empty + AddrMetadata(meta))())
  }

  test("filter rewrites host header when authority meta present") {
    val service = makeService(Addr.Metadata(Metadata.authority -> "acme.co")) { req =>
      val rsp = Response(Status.Ok)
      rsp.setContentString(req.host.get)
      rsp
    }

    val req = Request(Method.Get, "/")
    req.host = "monkeys"

    val result = await(service(req))
    assert(result.getContentString() == "acme.co")
  }

  test("filter doesn't rewrite host header normally") {
    val service = makeService(Addr.Metadata.empty) { req =>
      val rsp = Response(Status.Ok)
      rsp.setContentString(req.host.get)
      rsp
    }

    val req = Request(Method.Get, "/")
    req.host = "monkeys"

    val result = await(service(req))
    assert(result.getContentString() == "monkeys")
  }

  test("filter rewrites Refresh response header") {
    val service = makeService(Addr.Metadata(Metadata.authority -> "acme.co")) { req =>
      val rsp = Response(Status.Ok)
      rsp.headerMap.set("Refresh", s"5; url=http://${req.host.getOrElse("")}/bar")
      rsp
    }

    val req = Request(Method.Get, "/foo")
    req.host = "monkeys"

    val result = await(service(req))
    assert(result.headerMap.get("Refresh").contains("5; url=http://monkeys/bar"))
  }

  test("filter rewrites Location response header") {
    val service = makeService(Addr.Metadata(Metadata.authority -> "acme.co")) { req =>
      val rsp = Response(Status.Ok)
      rsp.headerMap.set(Fields.Location, s"http://${req.host.getOrElse("")}/bar")
      rsp
    }

    val req = Request(Method.Get, "/foo")
    req.host = "monkeys"

    val result = await(service(req))
    assert(result.headerMap.get(Fields.Location).contains("http://monkeys/bar"))
  }

  test("filter doesn't add Location/Refresh headers if they're not present in original response") {
    val service = makeService(Addr.Metadata(Metadata.authority -> "acme.co")) { req =>
      Response(Status.Ok)
    }

    val req = Request(Method.Get, "/foo")
    req.host = "monkeys"

    val result = await(service(req))
    assert(result.headerMap.get(Fields.Location).isEmpty)
    assert(result.headerMap.get("Refresh").isEmpty)
  }
}
