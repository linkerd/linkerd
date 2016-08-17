package io.buoyant.linkerd.protocol.http

import com.twitter.finagle._
import com.twitter.finagle.client.AddrMetadataExtraction.AddrMetadata
import com.twitter.finagle.http.{Status, _}
import com.twitter.util.Future
import io.buoyant.namer.Metadata
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class RewriteHostHeaderTest extends FunSuite with Awaits {

  def makeService(meta: Addr.Metadata) = {
    val svc = Service.mk[Request, Response] { req =>
      val rsp = Response()
      rsp.status = Status.Ok
      rsp.setContentString(req.host.get)
      Future.value(rsp)
    }

    val stk = RewriteHostHeader.module.toStack(
      Stack.Leaf(RewriteHostHeader.module.role, ServiceFactory.const(svc))
    )

    await(stk.make(Stack.Params.empty + AddrMetadata(meta))())
  }

  test("filter rewrites host header when authority meta present") {
    val service = makeService(Addr.Metadata(Metadata.authority -> "acme.co"))

    val req = Request(Method.Get, "/")
    req.host = "monkeys"

    val result = await(service(req))
    assert(result.getContentString() == "acme.co")
  }

  test("filter doesn't rewrite host header normally") {
    val service = makeService(Addr.Metadata.empty)

    val req = Request(Method.Get, "/")
    req.host = "monkeys"

    val result = await(service(req))
    assert(result.getContentString() == "monkeys")
  }
}
