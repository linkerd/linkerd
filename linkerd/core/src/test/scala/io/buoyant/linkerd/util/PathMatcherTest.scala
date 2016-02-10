package io.buoyant.linkerd.util

import com.twitter.finagle.Path
import org.scalatest.FunSuite

class PathMatcherTest extends FunSuite {

  test("exact match") {
    val matcher = PathMatcher("/foo/bar")
    assert(matcher.extract(Path.read("/foo/bar")) == Some(Map.empty))
  }

  test("prefix match") {
    val matcher = PathMatcher("/foo/bar")
    assert(matcher.extract(Path.read("/foo/bar/bas")) == Some(Map.empty))
  }

  test("mismatch") {
    val matcher = PathMatcher("/foo/bar")
    assert(matcher.extract(Path.read("/foo/goo")) == None)
  }

  test("path too short") {
    val matcher = PathMatcher("/foo/bar")
    assert(matcher.extract(Path.read("/foo")) == None)
  }

  test("wildcard match") {
    val matcher = PathMatcher("/foo/*/*")
    assert(matcher.extract(Path.read("/foo/hi/there")) == Some(Map.empty))
  }

  test("capture segments") {
    val matcher = PathMatcher("/foo/{A}")
    assert(matcher.extract(Path.read("/foo/bar/bas")) == Some(Map("A" -> "bar")))
  }

  test("capture and wildcards") {
    val matcher = PathMatcher("/foo/{A}/*/{B}")
    assert(matcher.extract(Path.read("/foo/boo/bar/bas")) == Some(Map("A" -> "boo", "B" -> "bas")))
  }

  test("failed capture") {
    val matcher = PathMatcher("/foo/{A}/bar")
    assert(matcher.extract(Path.read("/foo/bar/bad")) == None)
  }

  test("substitute") {
    val matcher = PathMatcher("/foo/{A}/*/{B}")
    assert(matcher.substitute(Path.read("/foo/boo/bar/bas"), "/{A}/and/{B}") == Some("/boo/and/bas"))
  }

  test("failed substitute") {
    val matcher = PathMatcher("/foo/{A}/*/{B}")
    assert(matcher.substitute(Path.read("/goo/boo/bar/bas"), "/{A}/and/{B}") == None)
  }
}
