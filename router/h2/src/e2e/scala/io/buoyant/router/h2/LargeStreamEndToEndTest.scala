package io.buoyant.router
package h2

import com.twitter.finagle.buoyant.h2._
import com.twitter.logging.Level
import com.twitter.util.{Future, Promise}
import io.buoyant.test.FunSuite

class LargeStreamEndToEndTest
  extends FunSuite
  with ClientServerHelpers {

  override val logLevel = Level.OFF

  val LargeStreamLen = 10 * 1024 * 1024 // == 10MB
  val FrameLen = 16 * 1024

  test("client/server large request stream") {
    val streamP = new Promise[Stream]
    val server = Downstream.mk("server") { req =>
      streamP.setValue(req.data)
      Response(Status.Ok, Stream.Nil)
    }
    val client = upstream(server.server)
    try {
      val writer = Stream()
      val req = Request("http", Method.Get, "host", "/path", writer)
      val rsp = await(client(req))
      assert(rsp.status == Status.Ok)
      await(defaultWait * 100) {
        testStream(reader(await(streamP)), writer, LargeStreamLen, 16 * 1024)
      }
    } finally {
      await(client.close())
      await(server.server.close())
    }
  }

  test("client/server large response stream") {
    val writer = Stream()
    val server = Downstream.mk("server") { _ => Response(Status.Ok, writer) }
    val client = upstream(server.server)
    try {
      val req = Request("http", Method.Get, "host", "/path", Stream.Nil)
      val rsp = await(client(req))
      assert(rsp.status == Status.Ok)
      await(defaultWait) {
        testStream(reader(rsp.data), writer, LargeStreamLen, FrameLen)
      }
    } finally {
      await(client.close())
      await(server.server.close())
    }
  }

  def testStream(
    reader: Stream.Reader,
    writer: Stream.Writer,
    streamLen: Long,
    frameSize: Int
  ): Future[Unit] = {
    def loop(bytesWritten: Long, ending: Boolean): Future[Unit] = {
      assert(bytesWritten <= streamLen)
      reader.read().flatMap {
        case t: Frame.Trailers =>
          fail(s"unexpected trailers $t")
          Future.Unit

        case d: Frame.Data if d.isEnd =>
          assert(bytesWritten == streamLen)
          d.release()

        case d: Frame.Data =>
          val eos = bytesWritten + d.buf.length >= streamLen
          val len = math.min(frameSize, streamLen - bytesWritten)
          val frame = Frame.Data(mkBuf(len.toInt), eos)
          d.release().before {
            if (ending) Future.Unit
            else writer.write(frame).before(loop(bytesWritten + len, eos))
          }
      }
    }

    writer.write(Frame.Data(mkBuf(frameSize), false))
      .before(loop(frameSize, false))
  }

}
