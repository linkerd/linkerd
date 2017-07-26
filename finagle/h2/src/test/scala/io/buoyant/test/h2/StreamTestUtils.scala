package io.buoyant.test.h2

import com.twitter.finagle.buoyant.h2.{Frame, Stream}
import com.twitter.util.Future

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
  final def readAll(stream: Stream): Future[Unit] =
    stream.read().flatMap { frame =>
      val end = frame.isEnd
      frame.release().before {
        if (end) Future.Done else readAll(stream)
      }
    }

  /**
    * Enhances a [[Stream]] by providing the [[readAll()]] function in the
    * method position
    * @param stream the underlying [[Stream]]
    */
  implicit class ReadAllStream(val stream: Stream) extends AnyVal {
    @inline def readAll: Future[Unit] = StreamTestUtils.readAll(stream)
  }

}