package io.buoyant.k8s

import com.fasterxml.jackson.core.`type`.TypeReference
import com.twitter.concurrent.AsyncStream
import com.twitter.finagle.{Failure, Filter, http}
import com.twitter.finagle.param.HighResTimer
import com.twitter.finagle.service.{Backoff, RetryBudget, RetryFilter, RetryPolicy}
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.tracing.Trace
import com.twitter.io.Reader
import com.twitter.util.TimeConversions._
import com.twitter.util.{NonFatal => _, _}
import java.util.concurrent.atomic.AtomicReference
import io.buoyant.k8s.Watch.{Added, Modified}
import scala.util.control.NonFatal

/**
 * An abstract class that encapsulates the ability to Watch a k8s [[Resource]].
 */
private[k8s] abstract class Watchable[O <: KubeObject: TypeReference, W <: Watch[O]: TypeReference, G <: KubeMetadata: TypeReference] extends Resource {
  import Watchable._

  protected def backoffs: Stream[Duration]
  protected def stats: StatsReceiver

  protected def infiniteRetryFilter = new RetryFilter[http.Request, http.Response](
    RetryPolicy.backoff(backoffs) {
      // We will assume 5xx are retryable, everything else is not for now
      case (_, Return(rep)) => rep.status.code >= 500 && rep.status.code < 600
      // Don't retry on interruption
      case (_, Throw(e: Failure)) if e.isFlagged(Failure.Interrupted) => false
      case (_, Throw(NonFatal(ex))) =>
        log.error(s"retrying k8s request to $path on error $ex")
        true
    },
    HighResTimer.Default,
    stats,
    RetryBudget.Infinite
  )

  def get(
    labelSelector: Option[String] = None,
    fieldSelector: Option[String] = None,
    resourceVersion: Option[String] = None,
    retryIndefinitely: Boolean = false,
    watch: Boolean = false
  ): Future[G] = {
    val req = Api.mkreq(
      http.Method.Get,
      if (watch) watchPath else path,
      None,
      "labelSelector" -> labelSelector,
      "fieldSelector" -> fieldSelector,
      "resourceVersion" -> resourceVersion
    )
    val retry = if (retryIndefinitely) infiniteRetryFilter else Filter.identity[http.Request, http.Response]
    val retryingClient = retry andThen client
    Trace.letClear(retryingClient(req)).flatMap(Api.parse[G])
  }

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
      val req = Api.mkreq(http.Method.Get, watchPath, None,
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

          case http.Status.Gone =>
            // Gone is returned by k8s to indicate the requested resource version is too old to watch. A common scenario
            // that exhibits this error is:
            //
            // 1. Kubernetes kills a connection or the connection is lost
            // 2. We try to re-establish the watch from the last received version, but because we are only watching a
            //    subset of resources, that version was a while ago
            // 3. The watch fails
            //
            // We need to reload the initial information to ensure we are "caught up," and then watch again.
            AsyncStream.fromFuture {
              restartWatches(labelSelector, fieldSelector).map {
                case (ws, ver) => AsyncStream.fromSeq(ws) ++ _watch(ver)
              }
            }.flatten

          case status =>
            close.set(Closable.nop)
            log.debug(s"k8s failed to watch resource $watchPath: ${status.code} ${status.reason}")
            val f = Future.exception(Api.UnexpectedResponse(rsp))
            AsyncStream.fromFuture(f)
        }
      }
    }

    (_watch(resourceVersion), Closable.ref(close))
  }

  /**
   * Convert this Watchable into an [[Activity]]
   * @param resourceVersion whether or not to send the initial resource
   *                        version of the watched resource. this is a
   *                        special case due to errors with the
   *                        ConfigMap interpreter
   * @param onEvent function called on each [[Watch]] event
   * TODO: i wish this returned a [[Activity.State]] so that the caller
   *       of this function could turn a [[Watch.Error]] into an
   *       [[Activity.Failed]] if they wanted watch errors to fail this
   *       [[Activity]], although none of our current code exhibits this
   *       use-case...
   *         - eliza, 7/18/2017
   * @return
   */
  def activity[T](convert: G => T, resourceVersion: Boolean = true)(onEvent: (T, W) => T): Activity[T] =
    Activity(Var.async[Activity.State[T]](Activity.Pending) { state =>
      val closeRef = new AtomicReference[Closable](Closable.nop)
      val pending = get(retryIndefinitely = true)
        // if the initial GET failed, then the activity is a failure
        .onFailure { e =>
          log.warning(s"k8s failed to get resource at $path: $e")
          state.update(Activity.Failed(e))
        }
        // otherwise, update the activity with the initial state, and
        // apply the onEvent function to each successive watch event in
        // the stream
        .onSuccess { initial =>
          val initialState = convert(initial)
          state.update(Activity.Ok(initialState))

          val version = if (resourceVersion) {
            initial.metadata.flatMap(_.resourceVersion)
          } else None

          val (stream, close) = watch(None, None, version)

          closeRef.set(close)
          val _ = stream.foldLeft(initialState) { (state0, event) =>
            val state1 = onEvent(state0, event)
            state.update(Activity.Ok(state1))
            state1
          }
        }

      Closable.make { t =>
        pending.raise(Closed)
        Closable.ref(closeRef).close(t)
      }

    })
}

object Watchable {
  def DefaultBackoff = Backoff.exponentialJittered(1.milliseconds, 5.seconds)
  private object Closed extends Throwable

  implicit class RichAsyncStream[T](as: AsyncStream[T]) {
    /**
     * Note: forces the stream. For infinite streams, the future never resolves.
     *
     * @return the last element in the stream, if any.
     */
    def lastOption: Future[Option[T]] = as.toSeq.map(_.lastOption)
  }
}
