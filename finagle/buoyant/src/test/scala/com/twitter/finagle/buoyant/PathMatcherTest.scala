package com.twitter.finagle.buoyant

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

  test("capture segments with endpoint") {
    val matcher = PathMatcher("/foo/bar/{A}:http")
    assert(matcher.extract(Path.read("/foo/bar/bas:http")) == Some(Map("A" -> "bas")))
  }

  test("capture and wildcards") {
    val matcher = PathMatcher("/foo/{A}/*/{B}")
    assert(matcher.extract(Path.read("/foo/boo/bar/bas")) == Some(Map("A" -> "boo", "B" -> "bas")))
  }

  test("capture segment from partial expression") {
    val matcher = PathMatcher("/foo/bar/{A}.com/{B}")
    assert(matcher.extract(Path.read("/foo/bar/foo.com/80")) == Some(Map("A" -> "foo", "B" -> "80")))

    val matcher2 = PathMatcher("/foo/bar/{A}-{B}/{C}")
    assert(matcher2.extract(Path.read("/foo/bar/foo-com/80")) == Some(Map("A" -> "foo", "B" -> "com", "C" -> "80")))

    val matcher3 = PathMatcher("/foo/bar/{A}.{B}/{C}")
    assert(matcher3.extract(Path.read("/foo/bar/foo.com/80")) == Some(Map("A" -> "foo", "B" -> "com", "C" -> "80")))

    val matcher4 = PathMatcher("/foo/bar/{A}.{B}.{C}/{D}")
    assert(matcher4.extract(Path.read("/foo/bar/www.foo.com/80")) == Some(Map("A" -> "www", "B" -> "foo", "C" -> "com", "D" -> "80")))

    val matcher5 = PathMatcher("/foo/{A}/*/{B}:http")
    assert(matcher5.extract(Path.read("/foo/boo/bar/bas:http")) == Some(Map("A" -> "boo", "B" -> "bas")))

    val matcher6 = PathMatcher("/foo/bar/{A}-{B}")
    assert(matcher6.extract(Path.read("/foo/bar/foo-bar-baz")) == Some(Map("A" -> "foo-bar", "B" -> "baz")))
  }

  test("failed capture") {
    val matcher = PathMatcher("/foo/{A}/bar")
    assert(matcher.extract(Path.read("/foo/bar/bad")) == None)
  }

  test("failed capture with endpoint") {
    val matcher = PathMatcher("/foo/{A}:http")
    assert(matcher.extract(Path.read("/foo/bar/bas:http")) == None)
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
