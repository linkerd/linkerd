package io.buoyant.router
package h2

import com.twitter.finagle.buoyant.h2._
import com.twitter.logging.Level
import com.twitter.util._
import io.buoyant.test.FunSuite

class LargeStreamEndToEndTest
  extends FunSuite
  with ClientServerHelpers {
  setLogLevel(Level.OFF)

  val WindowLen = 64 * 1024
  val FrameLen = 16 * 1024
  val LargeStreamLen = WindowLen * 2

  test(s"client/server ${LargeStreamLen}B request stream") {
    val streamP = new Promise[Stream]
    def serve(req: Request) = {
      streamP.setValue(req.stream)
      Response(Status.Ok, Stream.empty())
    }
    withClient(serve) { client =>
      val elapsed = Stopwatch.start()
      val writer = Stream()
      val req = Request("http", Method.Get, "host", "/path", writer)
      val rsp = await(client(req))
      assert(rsp.status == Status.Ok)
      await(defaultWait * 2) {
        testStream(await(streamP), writer, LargeStreamLen, FrameLen)
      }
      info(s"duration=${elapsed().inMillis}ms")
    }
  }

  test(s"client/server ${LargeStreamLen}B response stream") {
    val writer = Stream()
    try withClient(_ => Response(Status.Ok, writer)) { client =>
      val elapsed = Stopwatch.start()
      val req = Request("http", Method.Get, "host", "/path", Stream.empty())
      val rsp = await(client(req))
      assert(rsp.status == Status.Ok)
      await(defaultWait * 2) {
        testStream(rsp.stream, writer, LargeStreamLen, FrameLen)
      }
      info(s"duration=${elapsed().inMillis}ms")
    } finally setLogLevel(Level.OFF)
  }

  def testStream(
    reader: Stream,
    writer: Stream.Writer,
    streamLen: Long,
    frameSize: Int
  ): Future[Unit] = {
    def loop(bytesWritten: Long, ending: Boolean): Future[Unit] = {
      assert(bytesWritten <= streamLen)
      reader.read().transform {
        case Throw(e) => fail(e)

        case Return(t: Frame.Trailers) =>
          fail(s"unexpected trailers $t")
          Future.Unit

        case Return(d: Frame.Data) if d.isEnd =>
          assert(bytesWritten == streamLen)
          log.debug("releasing %s", d)
          d.release().onSuccess(_ => log.debug("released %s", d))

        case Return(d: Frame.Data) =>
          val eos = bytesWritten + d.buf.length >= streamLen
          val len = math.min(frameSize, streamLen - bytesWritten)
          val frame = Frame.Data(mkBuf(len.toInt), eos)
          log.debug("releasing %s", d)
          val releaseF = d.release()
          releaseF.onSuccess(_ => log.debug("released %s", d))
          releaseF.before {
            if (ending) Future.Unit
            else writer.write(frame).before(loop(bytesWritten + len, eos))
          }
      }
    }

    writer.write(Frame.Data(mkBuf(frameSize), false))
      .before(loop(frameSize, false))
  }

}
