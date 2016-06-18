package io.buoyant.k8s

import com.twitter.concurrent.AsyncStream
import com.twitter.finagle.http
import com.twitter.finagle.http.Response
import com.twitter.finagle.param.HighResTimer
import com.twitter.finagle.service.{Backoff, RetryBudget, RetryFilter, RetryPolicy}
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.tracing.Trace
import com.twitter.io.Reader
import com.twitter.util._
import com.twitter.util.TimeConversions._
import java.util.concurrent.atomic.AtomicReference

/**
 * An abstract class that encapsulates the ability to Watch a k8s [[Resource]].
 */
private[k8s] abstract class Watchable[O <: KubeObject: Manifest, W <: Watch[O]: Manifest]
  extends Resource {
  import Watchable._

  protected def backoffs: Stream[Duration]
  protected def path: String
  protected def stats: StatsReceiver

  /**
   * implementing classes should define this method to retrieve the current state
   * of the resource to pass as Modified results to any watchers.
   *
   * @return a Future containing a sequence of Watches and an optional String representing the current resourceVersion
   */
  protected def restartWatches(
    labelSelector: Option[String] = None,
    fieldSelector: Option[String] = None
  ): Future[(Seq[W], Option[String])]

  protected def infiniteRetryFilter = new RetryFilter[http.Request, http.Response](
    RetryPolicy.backoff(backoffs) {
      // We will assume 5xx are retryable, everything else is not for now
      case (_, Return(rep)) => rep.status.code >= 500 && rep.status.code < 600
      case (_, Throw(NonFatal(ex))) =>
        log.error(s"retrying k8s request to $path on error $ex")
        true
    },
    HighResTimer.Default,
    stats,
    RetryBudget.Infinite
  )

  /**
   * Watch this resource for changes, using a chunked HTTP request.
   *
   * TODO: investigate k8s websockets support as an alternative HTTP approach
   *
   * @return An asynchronous stream of [[Watch]] objects containing additions, modifications, and
   *         deletions to the items in the list being watched, and a Closable handle allowing for
   *         the watch to be terminated.
   */
  def watch(
    labelSelector: Option[String] = None,
    fieldSelector: Option[String] = None,
    resourceVersion: Option[String] = None
  ): (AsyncStream[W], Closable) = {
    val close = new AtomicReference[Closable](Closable.nop)

    // Internal method used to recursively retry watches as needed on failures.
    def _watch(resourceVersion: Option[String] = None): AsyncStream[W] = {
      val req = Api.mkreq(http.Method.Get, path, None,
        "watch" -> Some("true"),
        "labelSelector" -> labelSelector,
        "fieldSelector" -> fieldSelector,
        "resourceVersion" -> resourceVersion)
      val retryingClient = infiniteRetryFilter andThen client
      val initialState = Trace.letClear(retryingClient(req))

      close.set(Closable.make { _ =>
        log.trace("k8s watch cancelled")
        initialState.raise(Api.Closed)
        Future.Unit
      })

      AsyncStream.fromFuture(initialState).flatMap { rsp =>
        rsp.status match {
          // NOTE: 5xx-class statuses will be retried by the infiniteRetryFilter above.
          case http.Status.Ok =>
            close.set(Closable.make { _ =>
              log.debug("k8s watch closed")
              Future {
                rsp.reader.discard()
              } handle {
                case _: Reader.ReaderDiscarded =>
              }
            })
            val watchStream = Json.readStream[W](rsp.reader, Api.BufSize)
            watchStream ++ // if the stream ends (k8s will kill connections after ~30m), restart it)
              AsyncStream.fromFuture {
                // It's safe to call lastOption here, because we're after the `++` operator, so
                // this code will not be executed until `watchStream` has completed.
                val currentVersion = watchStream.lastOption.map(_.flatMap(_.resourceVersion))
                currentVersion.flatMap {
                  case Some(version) => Future.value(_watch(Some(version)))
                  case None =>
                    // In this case, we want to try loading the initial information instead before watching again.
                    restartWatches(labelSelector, fieldSelector).map {
                      case (ws, ver) =>
                        AsyncStream.fromSeq(ws) ++ _watch(ver)
                    }
                }
              }.flatten

          case status =>
            close.set(Closable.nop)
            log.debug(s"k8s failed to watch resource $path: ${status.code} ${status.reason}")
            val f = Future.exception(Api.UnexpectedResponse(rsp))
            AsyncStream.fromFuture(f)
        }
      }
    }

    (_watch(resourceVersion), Closable.ref(close))
  }
}

object Watchable {
  def DefaultBackoff = Backoff.exponentialJittered(1.milliseconds, 5.seconds)
  implicit class RichAsyncStream[T](as: AsyncStream[T]) {
    /**
     * Note: forces the stream. For infinite streams, the future never resolves.
     *
     * @return the last element in the stream, if any.
     */
    def lastOption: Future[Option[T]] = as.toSeq.map(_.lastOption)
  }
}
