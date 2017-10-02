package io.buoyant.k8s.istio

import com.twitter.finagle.{Addr, Path}
import com.twitter.finagle.buoyant.Dst
import com.twitter.util.Var
import io.buoyant.test.FunSuite

class CurrentIstioPathTest extends FunSuite {
  test("returns  when path has the expected number of segments") {
    val id = Path.Utf8(
      "1",
      "2",
      "3",
      "4",
      "5",
      "6",
      "7",
      "8",
      "9",
      "10"
    )

    val residual = Path.empty

    val bound = Dst.Bound(
      Var.value(Addr.Pending),
      id,
      residual
    )

    assert(CurrentIstioPath(Some(bound)) == Some(id))
  }

  test("returns null when not enough segments on path") {
    val id = Path.Utf8(
      "#",
      "io.l5d.k8s.istio",
      "productpage.default.svc.cluster.local",
      "version:v1",
      "http"
    )

    val residual = Path.empty

    val bound = Dst.Bound(
      Var.value(Addr.Pending),
      id,
      residual
    )

    assert(CurrentIstioPath(Some(bound)) == None)
  }

  test("returns null when no path bound") {
    assert(CurrentIstioPath(None) == None)
  }
}
