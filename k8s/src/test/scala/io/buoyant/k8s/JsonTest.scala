package io.buoyant.k8s

import com.twitter.io.{Buf, Reader}
import com.twitter.util._
import org.scalatest.FunSuite

private case class TestType(name: String, value: Option[TestType] = None)
private case class OtherType(huh: String, name: Option[String] = None)
private case class TypeWithSeq(name: String, values: Seq[TestType])

class JsonTest extends FunSuite {

  test("chunked: message") {
    val obj = TestType("foo", Some(TestType("bar")))
    val (objs, rest) = Json.readChunked[TestType](Json.writeBuf(obj))
    assert(objs == Seq(obj))
    assert(rest.isEmpty)
  }

  test("chunked: message + newline") {
    val obj = TestType("foo", Some(TestType("bar")))
    val (objs, rest) = Json.readChunked[TestType](Json.writeBuf(obj).concat(Buf.Utf8("\n")))
    assert(objs == Seq(obj))
    assert(rest == Buf.Utf8("\n"))
  }

  test("chunked: ignore unknown properties") {
    val (objs, rest) = Json.readChunked[TestType](Json.writeBuf(OtherType("foo", Some("bar"))))
    assert(objs == Seq(TestType("bar")))
  }

  test("chunked: 2 full objects and a partial object") {
    val obj0 = TestType("foo", Some(TestType("bar")))
    val obj1 = TestType("bah", Some(TestType("bat")))
    val obj2 = TestType("baf", Some(TestType("bal")))
    val readable = Json.writeBuf(obj0).concat(Json.writeBuf(obj1))
    val buf = readable.concat(Json.writeBuf(obj2))
    val (objs, rest) = Json.readChunked[TestType](buf.slice(0, buf.length - 7))
    assert(objs == Seq(obj0, obj1))
    assert(rest == buf.slice(readable.length, buf.length - 7))
  }

  // Failures inside arrays throw JsonMappingExceptions rather than JsonParseExceptions.
  test("chunked: object containing a collection") {
    val obj0 = TypeWithSeq("hello", Nil)
    val obj1 = TypeWithSeq("foo", Seq(TestType("foo", Some(TestType("bar"))), TestType("baz", Some(TestType("binky")))))
    val readable = Json.writeBuf(obj0)
    val buf = readable.concat(Json.writeBuf(obj1))
    val toChop = 10
    val (objs, rest) = Json.readChunked[TypeWithSeq](buf.slice(0, buf.length - toChop))
    assert(objs == Seq(obj0))
    assert(rest == buf.slice(readable.length, buf.length - toChop))
  }

  test("stream: messages") {
    val rw = Reader.writable()
    val objs = Seq(
      TestType("foo", Some(TestType("bar"))),
      TestType("bah", Some(TestType("bat"))),
      TestType("baf", Some(TestType("bal")))
    )
    val whole = objs.foldLeft(Buf.Empty)(_ concat Json.writeBuf(_))
    val sz = whole.length / objs.length
    val chunks = Seq(
      whole.slice(0, sz - 2),
      whole.slice(sz - 2, 2 * sz + 2),
      whole.slice(2 * sz + 2, whole.length)
    )

    var stream = Json.readStream[TestType](rw)

    var next = stream.uncons
    assert(!next.isDefined)

    // partial object
    Await.result(rw.write(chunks(0)))
    assert(!next.isDefined)

    // rest of first object, second object, and beginning of thrd object
    Await.result(rw.write(chunks(1)))
    assert(next.isDefined)

    stream = Await.result(next) match {
      case Some((obj, stream)) =>
        assert(obj == objs(0))
        stream()
      case ev => fail(s"unexpected event: $ev")
    }

    // a second object was written as well:
    next = stream.uncons
    assert(next.isDefined)

    stream = Await.result(next) match {
      case Some((obj, stream)) =>
        assert(obj == objs(1))
        stream()
      case ev => fail(s"unexpected event: $ev")
    }

    // third object hasn't been fully written yet
    next = stream.uncons
    assert(!next.isDefined)

    // write the third object
    Await.result(rw.write(chunks(2)))
    assert(next.isDefined)

    stream = Await.result(next) match {
      case Some((obj, stream)) =>
        assert(obj == objs(2))
        stream()
      case ev => fail(s"unexpected event: $ev")
    }

    // reader is waiting for more data
    next = stream.uncons
    assert(!next.isDefined)

    // but we close the stream instead
    Await.result(rw.close())
    assert(next.isDefined)
    assert(Await.result(next) == None)
  }
}
