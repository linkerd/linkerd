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

  // Tunable parameters:
  val concurrencies = Seq(8)

  val FrameSize = 16 * 1024
  val WindowSize = 4 * FrameSize
  val lengths = Seq(1, WindowSize * 2 - WindowSize / 2)

  case class Spec(len: Long, frameSize: Int, concurrency: Int)
  val specs = lengths.flatMap { len =>
    concurrencies.map { concurrency =>
      val frameSize = math.min(WindowSize / concurrency, FrameSize)
      Spec(len, frameSize, concurrency)
    }
  }

  val pfx = "client/server concurrent echos"

  for (Spec(streamLen, frameSize, concurrency) <- specs)
    test(s"$pfx: concurrency=${concurrency} len=${streamLen}B frame=${frameSize}B") {
      // The server simply echos the request stream into the response:
      val server = Downstream.service("server") { req =>
        Future(reader(req.data)).map(Response(Status.Ok, _))
      }
      val client = upstream(server.server)
      def open(): Future[Streamer] = {
        val s = Stream()
        client(Request("http", Method.Post, "host", "/", s))
          .map(r => Streamer(reader(r.data), s))
      }

      // Send the same data through all streams simultaneously, one frame at a time:
      def streamToAllInStep(streamers: Seq[Streamer], remaining: Long): Future[Unit] = {
        require(remaining > 0)
        val len = math.min(frameSize, remaining).toInt
        val buf = mkBuf(len)
        val eos = len == remaining
        val f = Future.collect(streamers.map(_.stream(buf, eos))).unit
        if (eos) f
        else f.before(streamToAllInStep(streamers, remaining - len))
      }

      try {
        val elapsed = Stopwatch.start()
        val streamers = Future.collect((0 until concurrency).map(_ => open()))
        await(streamers.flatMap(streamToAllInStep(_, streamLen)))
        info(s"duration=${elapsed().inMillis}ms")
      } finally {
        await(client.close())
        await(server.server.close())
      }
    }

  case class Streamer(reader: Stream.Reader, writer: Stream.Writer) {
    def stream(buf: Buf, eos: Boolean): Future[Unit] = {
      def read(remaining: Int): Future[Unit] =
        reader.read().flatMap {
          case d: Frame.Data =>
            (remaining - d.buf.length) match {
              case 0 =>
                assert(d.isEnd == eos)
                d.release()
              case remaining =>
                assert(!d.isEnd)
                d.release().join(read(remaining)).unit
            }

          case t: Frame.Trailers =>
            fail(s"unexpected trailers $t")
        }

      writer.write(Frame.Data(buf, eos)).before(read(buf.length))
    }
  }
}
