package io.buoyant.k8s

import com.fasterxml.jackson.core.`type`.TypeReference
import com.twitter.finagle.ChannelClosedException
import com.twitter.io.{Buf, Pipe, Reader}
import com.twitter.util._
import org.scalatest.FunSuite

private case class TestType(name: String, value: Option[TestType] = None)
private case class OtherType(huh: String, name: Option[String] = None)
private case class TypeWithSeq(name: String, values: Seq[TestType])

class JsonTest extends FunSuite {

  implicit private[this] val testTypeRef = new TypeReference[TestType] {}
  implicit private[this] val otherTypeRef = new TypeReference[OtherType] {}
  implicit private[this] val typeWithSeqRef = new TypeReference[TypeWithSeq] {}

  test("chunked: message") {
    val obj = TestType("foo", Some(TestType("bar")))
    val (decoded, rest) = Json.readBuf[TestType](Json.writeBuf(obj))
    assert(decoded == Seq(obj))
    assert(rest.isEmpty)
  }

  test("chunked: message + newline") {
    val obj = TestType("foo", Some(TestType("bar")))
    val (decoded, rest) = Json.readBuf[TestType](Json.writeBuf(obj).concat(Buf.Utf8("\n")))
    assert(decoded == Seq(obj))
    assert(rest == Buf.Utf8("\n"))
  }

  test("chunked: ignore unknown properties") {
    val obj = OtherType("foo", Some("bar"))
    val (decoded, rest) = Json.readBuf[TestType](Json.writeBuf(obj))
    assert(decoded == Seq(TestType("bar")))
  }

  test("chunked: 2 full objects and a partial object") {
    val obj0 = TestType("foo", Some(TestType("bar")))
    val obj1 = TestType("bah", Some(TestType("bat")))
    val obj2 = TestType("baf", Some(TestType("bal")))
    val readable = Json.writeBuf(obj0).concat(Json.writeBuf(obj1))
    val buf = readable.concat(Json.writeBuf(obj2))
    val (objs, rest) = Json.readBuf[TestType](buf.slice(0, buf.length - 7))
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
    val (decoded, rest) = Json.readBuf[TypeWithSeq](buf.slice(0, buf.length - toChop))
    assert(decoded == Seq(obj0))
    assert(rest == buf.slice(readable.length, buf.length - toChop))
  }

  test("stream: messages") {
    val rw = new Pipe[Buf]()
    val objs = Seq(
      TestType("foo", Some(TestType("bar"))),
      TestType("bah", Some(TestType("bat"))),
      TestType("baf", Some(TestType("bal")))
    )
    val whole = objs.foldLeft(Buf.Empty) { (b, o) =>
      b.concat(Json.writeBuf(o)).concat(Buf.Utf8("\n"))
    }
    val sz = whole.length / objs.length
    val chunks = Seq(
      whole.slice(0, sz - 2),
      whole.slice(sz - 2, 2 * sz + 2),
      whole.slice(2 * sz + 2, whole.length)
    )

    val decoded = Json.readStream[TestType](rw).toSeq()
    for (c <- chunks)
      Await.result(rw.write(c))
    Await.result(rw.close())

    assert(Await.result(decoded) == objs)
  }

  test("stream: large messages") {
    val obj = TestType("foo" * 10000, Some(TestType("inner")))
    val buf = Json.writeBuf(obj)
    val stream = Json.readStream[TestType](Reader.fromBuf(buf))
    val decoded = Await.result(stream.toSeq())
    assert(decoded == Seq(obj))
  }

  test("stream: empty chunk reads") {
    val rw = new Pipe[Buf]()
    val decoded = Json.readStream[TestType](rw).toSeq()

    val obj = TestType("foo", Some(TestType("inner")))
    val objBuf = Json.writeBuf(obj)
    Await.ready(rw.write(objBuf))
    Await.ready(rw.write(Buf.Utf8("\n"))) // Empty chunk
    Await.ready(rw.close())

    assert(Await.result(decoded) == Seq(obj))
  }

  test("stream: failing reader terminates stream") {
    val rw = new Pipe[Buf]()
    val decoded = Json.readStream[TestType](rw)

    val obj = TestType("foo", Some(TestType("inner")))
    Await.result(rw.write(Json.writeBuf(obj)))
    rw.fail(new ChannelClosedException)

    assert(Await.result(decoded.toSeq()) == Seq(obj))
  }
}
