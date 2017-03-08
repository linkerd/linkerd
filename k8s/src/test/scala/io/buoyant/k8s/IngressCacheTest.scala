package io.buoyant.k8s

import com.twitter.finagle.Path
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class IngressCacheTest extends FunSuite with Awaits {
  val host = Some("myhost")
  val ns = Some("ns")

  test("on multiple path matches, return first match") {
    val paths = Seq(
      IngressPath(host, Some("/path"), ns.get, "primary-svc", "80"),
      IngressPath(host, Some("/path"), ns.get, "secondary-svc", "80")
    )
    val spec = IngressSpec(Some("my-ingress"), ns, None, paths)
    val matchingPath = IngressCache.getMatchingPath(host, "/path", ns, Seq(spec))
    assert(matchingPath.get.svc == "primary-svc")
  }

  test("on multiple host matches, return first match") {
    val resource1 = IngressSpec(Some("polar-bear1"), ns, None, Seq(IngressPath(host, None, ns.get, "svc1", "80")))
    val resource2 = IngressSpec(Some("polar-bear2"), ns, None, Seq(IngressPath(host, None, ns.get, "svc2", "80")))
    val matchingPath = IngressCache.getMatchingPath(host, "/path", None, Seq(resource1, resource2))
    assert(matchingPath.get.svc == "svc1")
  }

  test("match on path prefix") {
    val paths = Seq(IngressPath(host, Some("/prefix"), ns.get, "svc1", "80"))
    val spec = IngressSpec(Some("my-ingress"), ns, None, paths)
    val matchingPath = IngressCache.getMatchingPath(host, "/prefix/and-other-stuff", None, Seq(spec))
    assert(matchingPath.isDefined)
  }

  test("don't match on path prefix if it's not a prefix on the path boundary") {
    val paths = Seq(IngressPath(host, Some("/prefix"), ns.get, "svc1", "80"))
    val spec = IngressSpec(Some("my-ingress"), ns, None, paths)
    val matchingPath = IngressCache.getMatchingPath(host, "/prefix707", None, Seq(spec))
    assert(matchingPath.isEmpty)
  }
}
