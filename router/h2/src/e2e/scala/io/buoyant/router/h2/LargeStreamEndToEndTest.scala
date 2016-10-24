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

  val LargeStreamLen = 100L * 1024 * 1024 // == 100MB

  test("client/server large request stream") {
    val streamP = new Promise[Stream]
    def serve(req: Request) = {
      streamP.setValue(req.data)
      Response(Status.Ok, Stream.Nil)
    }
    withClient(serve) { client =>
      val writer = Stream()
      val req = Request("http", Method.Get, "host", "/path", writer)
      val rsp = await(client(req))
      assert(rsp.status == Status.Ok)
      await(defaultWait * 100) {
        testStream(reader(await(streamP)), writer, LargeStreamLen, 16 * 1024)
      }
    }
  }

  test("client/server large response stream") {
    val writer = Stream()
    withClient(_ => Response(Status.Ok, writer)) { client =>
      val req = Request("http", Method.Get, "host", "/path", Stream.Nil)
      val rsp = await(client(req))
      assert(rsp.status == Status.Ok)
      await(defaultWait * 100) {
        testStream(reader(rsp.data), writer, LargeStreamLen, 16 * 1024)
      }
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
