package io.buoyant.router
package h2

import com.twitter.finagle.buoyant.h2._
import com.twitter.io.Buf
import com.twitter.logging.Level
import com.twitter.util._
import io.buoyant.test.FunSuite

class ConcurrentStreamsEndToEndTest
  extends FunSuite
  with ClientServerHelpers {

  override val logLevel = Level.OFF

  test("client/server concurrent streams") {
    // Tunable parameters:
    case class Spec(len: Long, frameSize: Int, concurrency: Int)
    val specs = Seq(2, 8, 64, 256).map { concurrency =>
      val frameSize = 64 * 1024 / (concurrency / 2)
      val len = 128 * 1024
      Spec(len, frameSize, concurrency)
    }

    // The server simply echos the request stream into the response:
    val server = Downstream.service("server") { req =>
      Future(reader(req.data)).map(Response(Status.Ok, _))
    }
    val client = upstream(server.server)
    def send(stream: Stream.Reader) =
      client(Request("http", Method.Post, "host", "/", stream))

    try {
      for (Spec(streamLen, frameSize, concurrency) <- specs) {
        val clock = Stopwatch.start()

        // Open `concurrency` streams at once:
        val streamers = await(defaultWait * concurrency) {
          val fs = (0 until concurrency).map { _ =>
            val s = Stream()
            send(s).map { r => Streamer(reader(r.data), s) }
          }
          Future.collect(fs)
        }

        // Send the same data through all streams simultaneously, one frame at a time:
        def streamFrameToAll(remaining: Long): Unit = {
          require(remaining > 0)
          val len = math.min(frameSize, remaining).toInt
          val buf = mkBuf(len)
          val eos = len == remaining
          await(defaultWait * concurrency) {
            Future.collect(streamers.map(_.stream(buf, eos))).unit
          }
          if (!eos) streamFrameToAll(remaining - len)
        }
        streamFrameToAll(streamLen)

        info(s"concurrency=${concurrency} len=${streamLen}B frame=${frameSize}B duration=${clock().inMillis}ms")
      }
    } finally {
      await(client.close())
      await(server.server.close())
    }
  }

  case class Streamer(reader: Stream.Reader, writer: Stream.Writer) {
    def stream(buf: Buf, eos: Boolean): Future[Unit] = {
      def readMore(remaining: Int): Future[Unit] =
        reader.read().flatMap {
          case d: Frame.Data =>
            (remaining - d.buf.length) match {
              case 0 =>
                assert(d.isEnd == eos)
                d.release()
              case remaining =>
                assert(!d.isEnd)
                d.release().before(readMore(remaining))
            }

          case t: Frame.Trailers =>
            fail(s"unexpected trailers $t")
            Future.Unit
        }

      await(writer.write(Frame.Data(buf, eos)))
      readMore(buf.length)
    }
  }
}
