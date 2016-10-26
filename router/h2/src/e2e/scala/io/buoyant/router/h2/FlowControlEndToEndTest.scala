package io.buoyant.router
package h2

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.buoyant.h2._
import com.twitter.io.Buf
import com.twitter.logging.Level
import com.twitter.util.{Future, Promise}
import io.buoyant.test.FunSuite
import scala.collection.mutable.ListBuffer

class FlowControlEndToEndTest
    extends FunSuite
    with ClientServerHelpers {

  override val logLevel = Level.OFF

  test("client/server request flow control") {
    val streamP = new Promise[Stream]
    val server = Downstream.mk("server") { req =>
      streamP.setValue(req.data)
      Response(Status.Ok, Stream.Nil)
    }
    val client = upstream(server.server)
    try {
      val q = new AsyncQueue[Frame]
      val req = Request("http", Method.Get, "host", "/path", Stream(q))
      val rsp = await(client(req))
      assert(rsp.status == Status.Ok)
      testFlowControl(reader(await(streamP)), q)
    } finally {
      await(client.close())
      await(server.server.close())
    }
  }

  test("client/server response flow control") {
    val q = new AsyncQueue[Frame]
    val server = Downstream.mk("server") { _ => Response(Status.Ok, Stream(q)) }
    val client = upstream(server.server)
    try {
      val req = Request("http", Method.Get, "host", "/path", Stream.Nil)
      val rsp = await(client(req))
      assert(rsp.status == Status.Ok)
      testFlowControl(reader(rsp.data), q)
    } finally {
      await(client.close())
      await(server.server.close())
    }
  }

  val WindowSize = 65535

  def testFlowControl(reader: Stream.Reader, writeQ: AsyncQueue[Frame]) = {
    // Put two windows' worth of data on the stream
    val release0, release1 = new Promise[Unit]
    def releaser(p: Promise[Unit]) = () => {
      p.setDone()
      Future.Unit
    }

    // The first frame is too large to fit in a window. It should not
    // be released until all of the data has been flushed.  This
    // cannot happen until the reader reads and erleases some of the
    // data.
    val frame0 = Frame.Data(mkBuf(WindowSize + 1024), false, releaser(release0))
    val frame1 = Frame.Data(mkBuf(WindowSize - 1024), true, releaser(release1))
    assert(!release0.isDefined && !release1.isDefined)
    log.debug("offering 2 frames with %d", 2 * WindowSize)
    assert(writeQ.offer(frame0))
    assert(writeQ.offer(frame1))
    assert(!release0.isDefined && !release1.isDefined)

    // Read a full window, without releasing anything.
    var read = 0
    val frames = ListBuffer.empty[Frame.Data]
    while (read < WindowSize) {
      await(reader.read()) match {
        case _: Frame.Trailers => fail("unexpected trailers")
        case d: Frame.Data =>
          frames += d
          read += d.buf.length
      }
    }
    assert(frames.map(_.buf.length).sum == read)
    assert(read == WindowSize)
    assert(!release0.isDefined && !release1.isDefined)

    // At this point, we've read an entire window of data from
    // the stream without releasing any of it.  Subsequent reads
    // should not complete until data is released.
    val rf0 = reader.read()
    for (_ <- 0 to 10000) assert(!rf0.isDefined)

    // Then, we release all of the pending data so that the window
    // updates.  Because window updates are aggregated, we can't be
    // incremental or we may not actually send an update.
    log.debug("reader releasing %dB from %d frames", read, frames.length-1)
    await(Future.collect(frames.map(_.release())).unit)
    frames.clear()

    // The second frame (release1) may or not have been released at
    // this point, depending on what window updates have been sent.

    // The pending read can complete now that window has opened and
    // the remote can send more.
    await(rf0) match {
      case _: Frame.Trailers => fail("unexpected trailers")
      case d: Frame.Data =>
        read += d.buf.length
        // Just release it immediately.
        log.debug("reader releasing %dB from 1 frame", d.buf.length)
        await(d.release())
    }

    // Read the remaining data and ensure that the second frame is released.
    while (read < 2 * WindowSize) {
      log.debug("reader reading more after %dB/%dB, released=%s",
        read, 2 * WindowSize, release1.isDefined)
      await(reader.read()) match {
        case _: Frame.Trailers => fail("unexpected trailers")
        case d: Frame.Data =>
          read += d.buf.length
          log.debug("reader releasing %dB from 1 frame", d.buf.length)
          await(d.release())
          assert(d.isEnd == (read == 2 * WindowSize))
      }
    }
    assert(read == 2 * WindowSize)
    eventually { assert(release1.isDefined) }
  }
}
