package io.buoyant.test.h2

import com.twitter.finagle.buoyant.h2.{Frame, Stream}
import com.twitter.io.Buf
import com.twitter.util.Future
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.handler.codec.http2._
import java.nio.charset.StandardCharsets

object StreamTestUtils {
  /**
   * Read a [[Stream]] to the end, [[Frame.release release()]]ing each
   * [[Frame]] before reading the next one.
   *
   * The value of each frame is discarded, but assertions can be made about
   * their contents by attaching an [[Stream.onFrame onFrame()]] callback
   * before calling `readAll()`.
   *
   * @param stream the [[Stream]] to read to the end
   * @return a [[Future]] that will finish when the whole stream is read
   */
  final def readToEnd(stream: Stream): Future[Unit] =
    if (stream.isEmpty) Future.Unit
    else
      stream.read().flatMap { frame =>
        val end = frame.isEnd
        frame.release().before {
          if (end) Future.Unit else readToEnd(stream)
        }
      }

  def readDataStream(stream: Stream): Future[ByteBuf] = {
    stream.read().flatMap {
      case frame: Frame.Data if frame.isEnd =>
        // Copy the data so that the underlying buffer can be released.
        val bbCopy = frame.buf.copy()
        val _ = frame.release()
        Future.value(bbCopy)
      case frame: Frame.Data =>
        // Copy the data so that the underlying buffer can be released.
        val bbCopy = frame.buf.copy()
        val _ = frame.release()
        readDataStream(stream).map { rest =>
          Unpooled.copiedBuffer(bbCopy, rest)
        }
      case frame: Frame.Trailers =>
        val _ = frame.release()
        Future.value(Unpooled.EMPTY_BUFFER)
    }
  }

  def readDataString(stream: Stream): Future[String] =
    readDataStream(stream).map(_.toString(StandardCharsets.UTF_8))

  /**
   * Enhances a [[Stream]] by providing the [[readToEnd()]] function in the
   * method position
   *
   * @param stream the underlying [[Stream]]
   */
  implicit class ReadAllStream(val stream: Stream) extends AnyVal {
    @inline def readToEnd: Future[Unit] = StreamTestUtils.readToEnd(stream)
    @inline def readDataString: Future[String] = StreamTestUtils.readDataString(stream)
  }

  def mkNewHeaderStreamFrame(hs: Http2Headers, streamId: Int, state: Http2Stream.State, eos: Boolean) =
    new DefaultHttp2HeadersFrame(hs, eos).stream(H2FrameStream(streamId, state))

}
