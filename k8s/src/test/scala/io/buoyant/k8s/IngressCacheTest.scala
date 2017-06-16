package io.buoyant.k8s

import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class IngressCacheTest extends FunSuite with Awaits {
  val host = Some("myhost")
  val ns = Some("ns")

  test("on multiple path matches, return first match") {
    val paths = Seq(
      IngressPath(host, Some("/path"), None, ns.get, "primary-svc", "80"),
      IngressPath(host, Some("/path"), None, ns.get, "secondary-svc", "80")
    )
    val spec = IngressSpec(Some("my-ingress"), ns, None, paths)
    val matchingPath = IngressCache.getMatchingPath(host, "/path", Seq(spec))
    assert(matchingPath.get.svc == "primary-svc")
  }

  test("on multiple host matches, return first match") {
    val resource1 = IngressSpec(Some("polar-bear1"), ns, None, Seq(IngressPath(host, None, None, ns.get, "svc1", "80")))
    val resource2 = IngressSpec(Some("polar-bear2"), ns, None, Seq(IngressPath(host, None, None, ns.get, "svc2", "80")))
    val matchingPath = IngressCache.getMatchingPath(host, "/path", Seq(resource1, resource2))
    assert(matchingPath.get.svc == "svc1")
  }

  test("match on path regex") {
    val testCases = Map(
      "/prefix" -> ingressPath(Some("/prefix.*")),
      "/prefix" -> ingressPath(Some("/prefix/?.*")),
      "/prefix/" -> ingressPath(Some("/prefix/.*")),
      "/prefix/and-other-stuff" -> ingressPath(Some("/prefix/.*")),
      "/path1/?foo=bar" -> ingressPath(Some("/path1/?.*")),
      "/path1?foo=bar/" -> ingressPath(Some("/path1/?.*")),
      "/path1/more/?foo=bar" -> ingressPath(Some("/path1/?.*"))
    )
    testCases foreach {
      case (requestUri, ingressPath) =>
        assert(ingressPath.matches(host, requestUri), s", '$requestUri' -> '${ingressPath.path.get}'")
    }
  }

  test("match on path regex with capturing group") {
    val testCases = Map(
      "/prefix" -> ingressPath(Some("/prefix(.*)")),
      "/prefix" -> ingressPath(Some("/prefix(/?.*)")),
      "/prefix/" -> ingressPath(Some("/prefix(/?.*)")),
      "/prefix/and-other-stuff" -> ingressPath(Some("/prefix/(.*)")),
      "/path1/?foo=bar" -> ingressPath(Some("/path1(/?.*)")),
      "/path1?foo=bar/" -> ingressPath(Some("/path1(/?.*)")),
      "/path1/more/?foo=bar" -> ingressPath(Some("/path1(/?.*)"))
    )
    testCases foreach {
      case (requestUri, ingressPath) =>
        assert(ingressPath.matches(host, requestUri), s", '$requestUri' -> '${ingressPath.path.get}'")
    }
  }

  test("match / with reqs that have empty paths only") {
    val path = IngressPath(host, Some("/"), None, ns.get, "svc1", "80")
    assert(path.matches(host, "/"))
    assert(!path.matches(host, "/foo"))
  }

  test("match empty string with all reqs") {
    val path = IngressPath(host, Some(""), None, ns.get, "svc1", "80")
    assert(path.matches(host, "/"))
    assert(path.matches(host, "/foo"))
  }

  test("match omitted path with all reqs") {
    val path = IngressPath(host, None, None, ns.get, "svc1", "80")
    assert(path.matches(host, "/"))
    assert(path.matches(host, "/foo"))
  }

  test("don't rewrite path when not enabled") {
    val testCases = Map(
      "/a/b/0" -> C("/a/b/0", ingressPath(None, None), false),
      "/a/b/1" -> C("/a/b/1", ingressPath(Some(""), None), false),
      "/a/b/2" -> C("/a/b/2", ingressPath(Some("/a/b/2"), None), false),
      "/a/b/3" -> C("/a/b/3", ingressPath(Some("/a/b/3"), Some(false)), false),
      "/a/b/4" -> C("/a/b/4", ingressPath(Some("/a/b/4"), Some(false)), true)
    )

    testCases foreach {
      case (requestUri, C(residualUri, ingressPath, globalFlag)) =>
        val result = ingressPath.rewrite(requestUri, globalFlag)
        assert(result == residualUri, s", test case: $requestUri -> $residualUri, $ingressPath, $globalFlag")
    }
  }

  test("rewrite path when enabled") {
    val testCases = Map(
      "/a/b/uri" -> C("/uri", ingressPath(Some("/a/b/(.*)"), Some(true)), false),
      "/a/b/uri" -> C("/uri", ingressPath(Some("/a/b/(.*)"), Some(true)), true),
      "/a/b/uri" -> C("/uri", ingressPath(Some("/a/b/(.*)"), None), true)
    )

    testCases foreach {
      case (requestUri, C(residualUri, ingressPath, globalFlag)) =>
        val result = ingressPath.rewrite(requestUri, globalFlag)
        assert(result == residualUri, s", test case: $requestUri -> $residualUri, $ingressPath, $globalFlag")
    }
  }

  test("rewrite path using capture groups") {
    val testCases = Map(
      "/nonexistent/" -> C("/", ingressPath(Some("/a/b/c"))),
      "/a/b/c/" -> C("/", ingressPath(Some("/a/b/c"))),
      "/a/b/c/" -> C("/a/b/c", ingressPath(Some("(/a/b/c)"))),

      "/a/b/c" -> C("/c", ingressPath(Some("/a/b/(.*)"))),
      "/a/b/1234/c" -> C("/1234/c", ingressPath(Some("/a/b/([0-9]*)/(.*)"))),
      "/a/b/1234/c/c" -> C("/1234/c", ingressPath(Some("/a/b/([0-9]*)/c/(.*)"))),
      "/a/b/1234/c/c/d" -> C("/1234/c", ingressPath(Some("/a/b/([0-9]*)/c/(.*)/d"))),

      "/a/b/c/" -> C("/c", ingressPath(Some("/a/b(.*)"))),
      "/a/b/c/" -> C("/c", ingressPath(Some("/a/b/(.*)"))),
      "/a/b/c/1" -> C("/c/1", ingressPath(Some("/a/b(/.*)"))),
      "/a/b/c/2" -> C("/c", ingressPath(Some("/a/b/(.*/)"))),
      "/a/b/c/3" -> C("/c", ingressPath(Some("/a/b(/.*/)"))),
      "/a/b/c/4" -> C("/", ingressPath(Some("/a/b/(/.*/)"))),
      "/a" -> C("/", ingressPath(Some("/a(/?.*)"))),
      "/a/" -> C("/", ingressPath(Some("/a(/?.*)"))),
      "/a/b" -> C("/b", ingressPath(Some("/a(/?.*)"))),
      "/a/b/" -> C("/b", ingressPath(Some("/a(/?.*)"))),
      "/a/b/c" -> C("/b/c", ingressPath(Some("/a(/?.*)"))),
      "/a/b/c/" -> C("/b/c", ingressPath(Some("/a(/?.*)"))),
      "/a/b/c/d" -> C("/b/c/d", ingressPath(Some("/a(/?.*)"))),
      "/a/b/c/d/" -> C("/b/c/d", ingressPath(Some("/a(/?.*)"))),
      "/a/b/?foo=bar#tag" -> C("/b/?foo=bar#tag", ingressPath(Some("/a(/?.*)")))
    )

    testCases foreach {
      case (requestUri, C(residualUri, ingressPath, globalFlag)) =>
        val result = ingressPath.rewrite(requestUri, globalFlag)
        assert(result == residualUri, s", test case: $requestUri -> $residualUri, $ingressPath, $globalFlag")
    }
  }

  case class C(uri: String, ingressPath: IngressPath, useCapturingGroupsGlobal: Boolean = true)

  def ingressPath(path: Option[String], useCapturingGroups: Option[Boolean] = Some(true)): IngressPath =
    IngressPath(host, path, useCapturingGroups, ns.get, "svc1", "80")
}
