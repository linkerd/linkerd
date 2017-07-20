package io.buoyant.router
package h2

import com.twitter.finagle.buoyant.h2._
import com.twitter.io.Buf
import com.twitter.logging.Level
import com.twitter.util.{Future, Promise}
import io.buoyant.test.FunSuite
import scala.collection.mutable.ListBuffer

class FlowControlEndToEndTest
  extends FunSuite
  with ClientServerHelpers {

  test("client/server request flow control") {
    val streamP = new Promise[Stream]
    val server = { req: Request =>
      streamP.setValue(req.stream)
      Response(Status.Ok, Stream.empty())
    }
    withClient(server) { client =>
      val writer = Stream()
      val req = Request("http", Method.Get, "host", "/path", writer)
      val rsp = await(client(req))
      assert(rsp.status == Status.Ok)
      testFlowControl(await(streamP), writer)
    }
  }

  test("client/server response flow control") {
    try {
      val writer = Stream()
      withClient(_ => Response(Status.Ok, writer)) { client =>
        val req = Request("http", Method.Get, "host", "/path", Stream.empty())
        val rsp = await(client(req))
        assert(rsp.status == Status.Ok)
        testFlowControl(rsp.stream, writer)
      }
    } finally setLogLevel(Level.OFF)
  }

  val WindowSize = 65535

  def testFlowControl(reader: Stream, writer: Stream.Writer): Unit = {
    // The first frame is too large to fit in a window. It should not
    // be released until all of the data has been flushed.  This
    // cannot happen until the reader reads and releases some of the
    // data.
    val frame0 = Frame.Data(mkBuf(WindowSize + 1024), false)
    val frame1 = Frame.Data(mkBuf(WindowSize - 1024), false)
    val frame2 = Frame.Data(mkBuf(WindowSize), true)

    log.debug("offering 2 frames with %d", 2 * WindowSize)
    val wrote0 = writer.write(frame0)
    val wrote1 = wrote0.before(writer.write(frame1))
    val wrote2 = wrote1.before(writer.write(frame2))
    assert(!wrote0.isDefined && !wrote1.isDefined && !wrote2.isDefined)

    // Read a full window, without releasing anything.
    var read = 0
    // val frames = ListBuffer.empty[Frame.Data]
    while (read < WindowSize) {
      log.debug("~~~ reading %d/%d", read, WindowSize)
      await(reader.read()) match {
        case d: Frame.Data =>
          // frames += d
          read += d.buf.length
          log.debug("~~~ read %d = %d/%d", d.buf.length, read, WindowSize)
          await(d.release())

        case f => fail(s"unexpected frame: $f")
      }
    }
    assert(read == WindowSize)
    await(wrote0)
    assert(!wrote2.isDefined)

    // Read the remaining data and ensure that the second frame is released.
    while (read < 2 * WindowSize) {
      log.debug("reader reading more after %dB/%dB, released=%s",
        read, 2 * WindowSize, wrote1.isDefined)
      if (read > WindowSize + 1024) assert(wrote0.isDefined)
      await(reader.read()) match {
        case d: Frame.Data =>
          read += d.buf.length
          log.debug("reader releasing %dB from 1 frame", d.buf.length)
          await(d.release())

        case f => fail(s"unexpected frame: $f")
      }
    }
    assert(read == 2 * WindowSize)
    await(wrote1)

    // Read the remaining data and ensure that the second frame is released.
    while (read < 3 * WindowSize) {
      log.debug("reader reading more after %dB/%dB, released=%s",
        read, 3 * WindowSize, wrote2.isDefined)
      await(reader.read()) match {
        case d: Frame.Data =>
          read += d.buf.length
          log.debug("reader releasing %dB from 1 frame", d.buf.length)
          await(d.release())
          assert(d.isEnd == (read == 3 * WindowSize))

        case f => fail(s"unexpected frame: $f")
      }
    }
    assert(read == 3 * WindowSize)
    ()
  }
}
