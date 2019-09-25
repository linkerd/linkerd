package io.buoyant.k8s.istio

import com.twitter.finagle.{Addr, Path}
import com.twitter.finagle.buoyant.Dst
import com.twitter.util.Var
import io.buoyant.test.FunSuite

class CurrentIstioPathTest extends FunSuite {
  test("returns path when path has transformer prefix") {
    val id = Path.Utf8(
      "%",
      "io.l5d.k8s.daemonset",
      "default",
      "incoming",
      "l5d",
      "#",
      "io.l5d.k8s.istio",
      "reviews.default.svc.cluster.local",
      "az:us-west::env:prod::version:v1",
      "http"
    )

    val residual = Path.empty

    val bound = Dst.Bound(
      Var.value(Addr.Pending),
      id,
      residual
    )

    assert(CurrentIstioPath(Some(bound)) == Some(id))
  }

  test("returns path when path doesn't have the transfomer prefix because it came from the ingress") {
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

    assert(CurrentIstioPath(Some(bound)) == Some(id))
  }

  test("returns null when no path bound") {
    assert(CurrentIstioPath(None) == None)
  }

  test("finds target service from path") {
    val expectedTargetFromLongPath = "reviews.default.svc.cluster.local"
    val targetFromLongPath = CurrentIstioPath.targetServiceIn(Path.Utf8(
      "%",
      "io.l5d.k8s.daemonset",
      "default",
      "incoming",
      "l5d",
      "#",
      "io.l5d.k8s.istio",
      expectedTargetFromLongPath,
      "az:us-west::env:prod::version:v1",
      "http"
    ))

    val expectedTargetFromShortPath = "productpage.default.svc.cluster.local"
    val targetFromShortPath = CurrentIstioPath.targetServiceIn(Path.Utf8(
      "#",
      "io.l5d.k8s.istio",
      expectedTargetFromShortPath,
      "version:v1",
      "http"
    ))

    val targetFromInvalidPath = CurrentIstioPath.targetServiceIn(Path.Utf8(
      "#",
      "http"
    ))

    assert(targetFromLongPath == Some(expectedTargetFromLongPath))
    assert(targetFromShortPath == Some(expectedTargetFromShortPath))
    assert(targetFromInvalidPath == None)
  }

  test("finds target version from path") {
    val expectedTargetFromLongPath = "cda3f1bdc2d55e487bc76dd77020a45a0305b74b"
    val targetFromLongPath = CurrentIstioPath.targetVersionIn(Path.Utf8(
      "%",
      "io.l5d.k8s.daemonset",
      "default",
      "incoming",
      "l5d",
      "#",
      "io.l5d.k8s.istio",
      "reviews.default.svc.cluster.local",
      s"az:us-west::env:prod::version:$expectedTargetFromLongPath",
      "http"
    ))

    val expectedTargetFromShortPath = "v42"
    val targetFromShortPath = CurrentIstioPath.targetVersionIn(Path.Utf8(
      "#",
      "io.l5d.k8s.istio",
      "productpage.default.svc.cluster.local",
      s"version:$expectedTargetFromShortPath",
      "http"
    ))

    val targetFromInvalidPath = CurrentIstioPath.targetVersionIn(Path.Utf8(
      "#",
      "http"
    ))

    assert(targetFromLongPath == Some(expectedTargetFromLongPath))
    assert(targetFromShortPath == Some(expectedTargetFromShortPath))
    assert(targetFromInvalidPath == None)
  }

  test("finds target app from path") {
    val expectedTargetFromLongPath = "reviews"
    val targetFromLongPath = CurrentIstioPath.targetAppIn(Path.Utf8(
      "%",
      "io.l5d.k8s.daemonset",
      "default",
      "incoming",
      "l5d",
      "#",
      "io.l5d.k8s.istio",
      s"${expectedTargetFromLongPath}.default.svc.cluster.local",
      "az:us-west::env:prod::version:v1",
      "http"
    ))

    val expectedTargetFromShortPath = "productpage"
    val targetFromShortPath = CurrentIstioPath.targetAppIn(Path.Utf8(
      "#",
      "io.l5d.k8s.istio",
      s"${expectedTargetFromShortPath}.default.svc.cluster.local",
      "version:v1",
      "http"
    ))

    val targetFromInvalidPath = CurrentIstioPath.targetAppIn(Path.Utf8(
      "#",
      "http"
    ))

    assert(targetFromLongPath == Some(expectedTargetFromLongPath))
    assert(targetFromShortPath == Some(expectedTargetFromShortPath))
    assert(targetFromInvalidPath == None)
  }
}
