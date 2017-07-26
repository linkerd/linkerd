package com.twitter.finagle.buoyant.h2

import com.twitter.conversions.storage._
import com.twitter.conversions.time._
import com.twitter.finagle.buoyant.h2.service.{H2ReqRep, H2StreamClassifier}
import com.twitter.finagle.service.ResponseClass
import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.util._
import scala.{Stream => SStream}

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
  classifier: H2StreamClassifier,
  backoffs: SStream[Duration],
  requestBufferSize: Long = 8.kilobytes.bytes,
  responseBufferSize: Long = 8.kilobytes.bytes
)(implicit timer: Timer) extends SimpleFilter[Request, Response] {
  // TODO: accept a Stream of backoffs

  override def apply(
    request: Request,
    service: Service[Request, Response]
  ): Future[Response] = {

    // Buffer the request stream so that we can fork another child stream if we need to retry
    val requestBuffer = new BufferedStream(request.stream, requestBufferSize)
    val fork = Future.const(requestBuffer.fork())

    def dispatch(reqStream: Stream, backoffs: SStream[Duration]): Future[Response] = {
      val req = Request(request.headers.dup(), reqStream)

      // Attempt to retry.
      // If a retry is not possible because the request buffer has been discarded or we have run
      // out of retries.
      @inline def retry(orElse: => Future[Response], onRetry: => Unit = ()): Future[Response] = {
        requestBuffer.fork() match {
          case Return(s) =>
            backoffs match {
              case pause #:: rest =>
                // Retry!
                onRetry
                schedule(pause)(dispatch(s, rest))
              case _ =>
                // We ran out of retries.
                orElse
            }
          case Throw(e) =>
            // We could not create a new child request stream so just return the response stream
            orElse
        }
      }

      service(req).flatMap { rsp =>
        // Buffer the response stream so that we can attempt to classify it before returning
        // or discarding it
        val responseBuffer = new BufferedStream(rsp.stream, responseBufferSize)
        // We eagerly create a child response stream since we need something to return in case we
        // don't want to (or can't) retry.
        val responseStream = responseBuffer.fork()

        // Discard the buffers and return the current response stream
        @inline def discardAndReturn(): Future[Response] = {
          requestBuffer.discardBuffer()
          responseBuffer.discardBuffer()
          Future.const(responseStream).map(Response(rsp.headers, _))
        }

        // We will retry so discard the current response stream and response stream buffer
        @inline def discardResponse(): Unit = {
          responseBuffer.discardBuffer()
          responseStream.foreach { rs => consumeAll(rs); () }
        }

        retryable(req, rsp, responseBuffer).flatMap { retryable =>
          if (retryable) {
            // Request is retryable, attempt to create a new child request stream
            retry(orElse = discardAndReturn(), onRetry = discardResponse())
          } else {
            // Request is not retryable so just return the response stream
            discardAndReturn()
          }
        }
      }.rescue {
        case e if classifier(H2ReqRep(req, Throw(e))) == ResponseClass.RetryableFailure =>
          // Request is retryable, attempt to create a new child request stream
          retry(orElse = Future.exception(e))
      }
    }

    fork.flatMap(dispatch(_, backoffs))
  }

  @inline
  private[this] def schedule(d: Duration)(f: => Future[Response]) = {
    if (d > 0.seconds) {
      val promise = new Promise[Response]
      timer.schedule(Time.now + d) {
        promise.become(f)
      }
      promise
    } else f
  }

  private[this] def retryable(req: Request, rsp: Response, responseBuffer: BufferedStream): Future[Boolean] = {
    // Create a child response stream for the sole purpose of getting the last frame for
    // response classification and determine if the request is retryable.
    responseBuffer.fork() match {
      case Return(s) =>
        // Attempt to determine retryability based on the final frame.  Completes when the stream is
        // fully buffered.
        val fullyBuffered = retryable(req, rsp, s)
        // If the buffer is discarded before reading the final frame, we cannot retry.
        val bufferDiscarded = responseBuffer.onBufferDiscarded.map(_ => false)

        // Wait until the response stream is fully buffered or the buffer becomes full.
        Future.select(Seq(fullyBuffered, bufferDiscarded))
          .flatMap(select => Future.const(select._1))
      case Throw(e) =>
        Future.False
    }
  }

  /**
   * Determine if the request is retryable.  Will read and release the entire response stream
   * as a side effect.
   */
  private[this] def retryable(req: Request, rsp: Response, responseStream: Stream): Future[Boolean] = {
    consumeAllButLast(responseStream).transform {
      case Return(Some(f)) =>
        val canRetry = classifier(H2ReqRep(req, Return(rsp, Some(Return(f))))) == ResponseClass.RetryableFailure
        f.release()
        Future.value(canRetry)
      case Return(None) =>
        val canRetry = classifier(H2ReqRep(req, Return(rsp, None))) == ResponseClass.RetryableFailure
        Future.value(canRetry)
      case Throw(e) =>
        val canRetry = classifier(H2ReqRep(req, Return(rsp, Some(Throw(e))))) == ResponseClass.RetryableFailure
        Future.value(canRetry)
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
