package io.buoyant.k8s

import com.twitter.finagle.Path
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class IngressCacheTest extends FunSuite with Awaits {
  def resourceFixture(host: Option[String], name: Option[String], paths: Seq[IngressPath]) = {
    val pathResource = paths.map(path => v1beta1.HTTPIngressPath(path.path.map(_.show),v1beta1.IngressBackend(path.svc, path.port)))
    val http = v1beta1.HTTPIngressRuleValue(pathResource)
    v1beta1.IngressList(Seq(
      v1beta1.Ingress(
        Some(v1beta1.IngressSpec(None, None, Some(Seq(v1beta1.IngressRule(host, Some(http)))))),
        None,
        Some("list"),
        Some(ObjectMeta(name, name, paths.headOption.map(_.namespace))))
    ))
  }

  val host = Some("myhost")

  test("on multiple path matches, return first match") {
    val cache = new IngressCache(None)
    val paths = Seq(
      IngressPath(host, Some(Path.Utf8("path")), "default", "primarySvc", "80"),
      IngressPath(host, Some(Path.Utf8("path")), "default", "secondarySvc", "80")
    )

    cache.initialize(resourceFixture(host, Some("ing-resource"), paths))
    val matchingPath = await(cache.getMatchingPath(host, "/path"))
    assert(matchingPath.get.svc == "primarySvc")
  }

  test("on multiple host matches, return first match") {
    val cache = new IngressCache(None)
    val resource1 = resourceFixture(host, Some("r1"), Seq(IngressPath(host, None, "default", "svc1", "80")))
    val resource2 = resourceFixture(host, Some("r2"), Seq(IngressPath(host, None, "default", "svc2", "80")))

    cache.initialize(v1beta1.IngressList(resource1.items ++ resource2.items))
    val matchingPath = await(cache.getMatchingPath(host, "/path"))

    assert(matchingPath.get.svc == "svc1")
  }

  test("match on path prefix") {
    val cache = new IngressCache(None)
    val paths = Seq(
      IngressPath(host, Some(Path.read("/prefix")), "default", "svc1", "80")
    )
    cache.initialize(resourceFixture(host, Some("ing-resource"), paths))
    val matchingPath = await(cache.getMatchingPath(host, "/prefix/and-other-stuff"))

    assert(matchingPath.isDefined)
  }

  test("don't match on path prefix if it's not a prefix on the path boundary") {
    val cache = new IngressCache(None)
    val paths = Seq(
      IngressPath(host, Some(Path.read("/prefix")), "default", "svc1", "80")
    )
    cache.initialize(resourceFixture(host, Some("ing-resource"), paths))
    val matchingPath = await(cache.getMatchingPath(host, "/prefix707"))

    assert(matchingPath.isEmpty)
  }

}
