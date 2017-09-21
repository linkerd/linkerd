package io.buoyant.http

import com.twitter.finagle.{Status => _, _}
import com.twitter.finagle.http._
import io.buoyant.test.FunSuite

class StatusTest extends FunSuite {

  def lookup(path: Path) =
    await(Namer.global.lookup(path).values.toFuture).get

  test("status") {
    val client = Http.newService("/$/io.buoyant.http.status/401/foo/bar.a.b/bah")
    val rsp = await(client(Request()))
    assert(rsp.status == Status.Unauthorized)
  }

  test("status: invalid") {
    val path = Path.read("/$/io.buoyant.http.status/foo/bar.a.b/bah")
    assert(lookup(path) == NameTree.Neg)
  }

  test("status: no code") {
    val path = Path.read("/$/io.buoyant.http.status")
    assert(lookup(path) == NameTree.Neg)
  }

}
