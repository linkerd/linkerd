package com.twitter.finagle.buoyant.h2

import com.twitter.concurrent.AsyncQueue
import com.twitter.io.Buf
import io.buoyant.test.FunSuite

class BufferedStreamTest extends FunSuite {

  val Hello = Buf.Utf8("hello")

  test("fans out to multiple children") {
    val sourceQ = new AsyncQueue[Frame]()
    val source = Stream(sourceQ)
    val buffer = new BufferedStream(source)

    // fork children
    val children = (1 to 10).map(_ => buffer.fork().get)

    // send frame
    val data = Frame.Data(Hello, eos = false)
    sourceQ.offer(data)

    // children read frame
    for (child <- children) {
      val f = await(child.read()).asInstanceOf[Frame.Data]
      assert(f.buf == Hello)
      await(f.release())
    }

    // send eos
    val trailer = Frame.Trailers("good" -> "bye")
    sourceQ.offer(trailer)

    //children read eos
    for (child <- children) {
      val f = await(child.read()).asInstanceOf[Frame.Trailers]
      assert(f.get("good") == Some("bye"))
      await(f.release())
    }

    // frames should not be released as they are still held by the buffer
    assert(!data.onRelease.isDefined)
    assert(!trailer.onRelease.isDefined)

    buffer.discardBuffer()
    // after discarding, forking is no longer possible
    assert(buffer.fork().isThrow)
    // all streams should now be complete
    for (child <- children) {
      assert(child.onEnd.isDone)
    }
    assert(data.onRelease.isDefined)
    assert(trailer.onRelease.isDefined)
    assert(source.onEnd.isDone)
  }

  test("sending frames after dicarding buffer") {
    val sourceQ = new AsyncQueue[Frame]()
    val source = Stream(sourceQ)
    val buffer = new BufferedStream(source)

    // fork children
    val children = (1 to 10).map(_ => buffer.fork().get)

    // send frame
    val data = Frame.Data(Hello, eos = false)
    sourceQ.offer(data)

    // children read frame
    for (child <- children) {
      val f = await(child.read()).asInstanceOf[Frame.Data]
      assert(f.buf == Hello)
      await(f.release())
    }

    buffer.discardBuffer()

    // after discarding, forking is no longer possible
    assert(buffer.fork().isThrow)

    // frame has been released by all children and the buffer
    assert(data.onRelease.isDefined)

    // send eos
    val trailer = Frame.Trailers("good" -> "bye")
    sourceQ.offer(trailer)
    // frame should not be released until it is released by all children
    assert(!trailer.onRelease.isDefined)

    //children read eos
    for (child <- children) {
      val f = await(child.read()).asInstanceOf[Frame.Trailers]
      assert(f.get("good") == Some("bye"))
      await(f.release())
    }

    assert(trailer.onRelease.isDefined)
    assert(source.onEnd.isDone)
  }

  test("forking with frames in the buffer") {
    val sourceQ = new AsyncQueue[Frame]()
    val source = Stream(sourceQ)
    val buffer = new BufferedStream(source)

    // send frames
    val frames = (1 to 10).map { i =>
      val f = Frame.Data(i.toString, eos = false)
      sourceQ.offer(f)
      f
    }

    // fork children
    val children = for (_ <- 1 to 10) yield {
      val child = buffer.fork().get
      for (i <- 1 to 10) {
        val frame = await(child.read()).asInstanceOf[Frame.Data]
        assert(frame.buf == Buf.Utf8(i.toString))
        await(frame.release())
      }
      child
    }
    buffer.discardBuffer()

    // send eos
    val trailer = Frame.Trailers("good" -> "bye")
    sourceQ.offer(trailer)

    //children read eos
    for (child <- children) {
      val f = await(child.read()).asInstanceOf[Frame.Trailers]
      assert(f.get("good") == Some("bye"))
      await(f.release())
    }
  }

  test("buffer becomes full") {
    val sourceQ = new AsyncQueue[Frame]()
    val source = Stream(sourceQ)
    val buffer = new BufferedStream(source, bufferCapacity = 10)

    // send frames
    sourceQ.offer(Frame.Data(Buf.ByteArray(0, 1, 2, 3, 4), eos = false))
    sourceQ.offer(Frame.Data(Buf.ByteArray(5, 6, 7, 8, 9), eos = false))
    sourceQ.offer(Frame.Data(Buf.ByteArray(10, 11, 12, 13, 14), eos = false))

    val child = buffer.fork().get

    val frame1 = await(child.read()).asInstanceOf[Frame.Data]
    val frame2 = await(child.read()).asInstanceOf[Frame.Data]

    assert(!buffer.onBufferDiscarded.isDone)

    // not enough room for this in the buffer; discard the buffer
    val frame3 = await(child.read()).asInstanceOf[Frame.Data]

    assert(buffer.onBufferDiscarded.isDone)
    assert(buffer.fork().isThrow)

    // even after buffer is discarded, frames should be fanned out to existing children
    sourceQ.offer(Frame.Data(Buf.ByteArray(15, 16, 17, 18, 19), eos = true))
    val frame4 = await(child.read()).asInstanceOf[Frame.Data]

    assert(frame1.buf == Buf.ByteArray(0, 1, 2, 3, 4))
    assert(frame2.buf == Buf.ByteArray(5, 6, 7, 8, 9))
    assert(frame3.buf == Buf.ByteArray(10, 11, 12, 13, 14))
    assert(frame4.buf == Buf.ByteArray(15, 16, 17, 18, 19))

    frame1.release()
    frame2.release()
    frame3.release()
    frame4.release()
  }
}
