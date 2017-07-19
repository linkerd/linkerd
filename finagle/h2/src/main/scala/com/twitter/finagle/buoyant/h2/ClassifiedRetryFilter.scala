package com.twitter.finagle.buoyant.h2

import com.twitter.conversions.storage._
import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.util.{Future, Return, Throw}

/**
 * The ClassifiedRetyFilter uses BufferedStreams to implement retries.  The request stream is
 * buffered and a child stream is sent to the service.  This is done so that if it becomes
 * necessary to retry, a new child of a the request stream can be created.
 *
 * The response stream is also buffered and held until either the response stream completes
 * or the response buffer becomes full.  If the response stream completes, we use the provided
 * ResponseClassifier to determine if the request should be retried.  If so, we discard the
 * response stream, fork a new child of the request stream, and send the new request stream to
 * the service.  If not, we return the response stream to the caller.
 */
class ClassifiedRetryFilter(
  requestBufferSize: Long = 8.kilobytes.bytes,
  responseBufferSize: Long = 8.kilobytes.bytes
) extends SimpleFilter[Request, Response] {
  // TODO: accept a ResponseClassifier
  // TODO: accept a Stream of backoffs

  override def apply(
    request: Request,
    service: Service[Request, Response]
  ): Future[Response] = {

    // Buffer the request stream so that we can fork another child stream if we need to retry
    val requestBuffer = new BufferedStream(request.stream, requestBufferSize)
    val fork = Future.const(requestBuffer.fork())
    // begin reading the request stream
    requestBuffer.read()

    def dispatch(reqStream: Stream): Future[Response] = {
      val req = Request(request.headers.dup(), reqStream)
      service(req).flatMap { rsp =>
        // Buffer the response stream so that we can attempt to classify it before returning
        // or discarding it
        val responseBuffer = new BufferedStream(rsp.stream, responseBufferSize)
        // We eagerly create a child response stream since we need something to return in case we
        // don't want to (or can't) retry.
        val responseStream = responseBuffer.fork()
        val onFullyBufferd = responseBuffer.read()
        // Wait until the response stream is fully buffered or the buffer becomes full
        Future.selectIndex(IndexedSeq(onFullyBufferd, responseBuffer.onBufferDiscarded)).flatMap {
          // Response stream is fully buffered
          case 0 =>
            // Create a child response stream for the sole purpose of getting the last frame for
            // response classification and determine if the request is retryable.
            responseBuffer.fork() match {
              case Return(s) => retryable(req, rsp, s)
              case Throw(e) => Future.False
            }
          // Response buffer is full
          case 1 =>
            // Cannot retry because the buffer was not big enough to wait for the final response
            // Frame.
            Future.False
          case _ =>
            throw new IllegalStateException()
        }.flatMap { retryable =>
          if (retryable) {
            // Request is retryable, attempt to create a new child request stream
            requestBuffer.fork() match {
              case Return(s) =>
                // We will retry so discard the response stream and response stream buffer
                responseBuffer.discardBuffer()
                responseStream.foreach { rs => consumeAll(rs); () }
                // Retry!
                dispatch(s)
              case Throw(e) =>
                // We could not create a new child request stream so just return the response stream
                // We're all done here so discard all the buffers.
                requestBuffer.discardBuffer()
                responseBuffer.discardBuffer()
                Future.const(responseStream).map(Response(rsp.headers, _))
            }
          } else {
            // Request is not retryable so just return the response stream
            // We're all done here so discard all the buffers.
            requestBuffer.discardBuffer()
            responseBuffer.discardBuffer()
            Future.const(responseStream).map(Response(rsp.headers, _))
          }
        }
      }
    }

    fork.flatMap(dispatch)
  }

  /**
   * Determine if the request is retryable.  Will read and release the entire response stream
   * as a side effect.
   */
  private[this] def retryable(req: Request, rsp: Response, responseStream: Stream): Future[Boolean] = {
    consumeAllButLast(responseStream).map {
      // TODO: consult response classifier
      case Some(t: Frame.Trailers) =>
        t.release()
        t.get("retry").contains("true")
      case Some(d: Frame.Data) =>
        d.release()
        false
      case None => false
    }
  }

  /** Read and release all frames from the Stream */
  private[this] def consumeAll(stream: Stream): Future[Unit] = {
    consumeAllButLast(stream).map {
      case Some(frame) =>
        frame.release(); ()
      case None => ()
    }
  }

  /** Read and release all but the last frame from a Stream.  Then return the last frame */
  private[this] def consumeAllButLast(stream: Stream): Future[Option[Frame]] =
    if (stream.isEmpty) {
      Future.None
    } else {
      stream.read().flatMap { frame =>
        if (frame.isEnd) {
          Future.value(Some(frame))
        } else {
          frame.release()
          consumeAllButLast(stream)
        }
      }
    }
}
