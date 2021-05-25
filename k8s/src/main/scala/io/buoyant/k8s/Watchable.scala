package io.buoyant.k8s

import java.util.concurrent.atomic.AtomicReference

import com.fasterxml.jackson.core.`type`.TypeReference
import com.twitter.concurrent.AsyncStream
import com.twitter.finagle.buoyant.RetryFilter
import com.twitter.finagle.param.HighResTimer
import com.twitter.finagle.service.{RetryBudget, RetryPolicy}
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.tracing.Trace
import com.twitter.finagle.util.DefaultTimer
import com.twitter.finagle.{Backoff, Failure, FailureFlags, Filter, http}
import com.twitter.io.ReaderDiscardedException
import com.twitter.conversions.DurationOps._
import com.twitter.util._
import io.buoyant.namer.InstrumentedActivity
import scala.util.control.NonFatal

/**
 * An abstract class that encapsulates the ability to Watch a k8s [[Resource]].
 */
private[k8s] abstract class Watchable[O <: KubeObject: TypeReference, W <: Watch[O]: Ordering: TypeReference, G <: KubeMetadata: TypeReference] extends Resource {
  import Watchable._

  protected def backoffs: Backoff
  protected def stats: StatsReceiver
  protected implicit val timer = DefaultTimer

  // whether or not to pass resource versions on watches; this should be true
  // for List resources & false for object resources.
  // TODO: there is probably a more elegant way to represent this...
  protected val watchResourceVersion: Boolean

  protected def infiniteRetryFilter = new RetryFilter[http.Request, http.Response](
    RetryPolicy.backoff(backoffs) {
      // 2XX, 404, and 411 are considered valid responses and should not be retried
      case (_, Return(rep)) if rep.status.code >= 200 && rep.status.code < 300 => false
      case (_, Return(rep)) if rep.status == http.Status.NotFound || rep.status == http.Status.Gone => false
      // All other status codes are unexpected and should be retried
      case (_, Return(rep)) =>
        log.error("retrying k8s request to %s on unexpected response code %d with message %s", path, rep.statusCode, rep.contentString)
        true
      // Don't retry on interruption
      case (_, Throw(e: Failure)) if e.isFlagged(FailureFlags.Interrupted) => false
      // Don't retry on reader discarded
      case (_, Throw(_: ReaderDiscardedException)) => false
      case (_, Throw(NonFatal(ex))) =>
        log.warning("retrying k8s request to %s on error %s", path, ex)
        true
    },
    HighResTimer.Default,
    stats,
    RetryBudget.Infinite,
    _.reader.discard()
  )

  def get(
    labelSelector: Option[String] = None,
    fieldSelector: Option[String] = None,
    resourceVersion: Option[String] = None,
    retryIndefinitely: Boolean = false,
    watchState: Option[WatchState[G, W]] = None
  ): Future[Option[G]] = {
    val req = Api.mkreq(
      http.Method.Get,
      path,
      None,
      LabelSelectorKey -> labelSelector,
      FieldSelectorKey -> fieldSelector,
      ResourceVersionKey -> resourceVersion
    )

    val retry =
      if (retryIndefinitely) infiniteRetryFilter
      else Filter.identity[http.Request, http.Response]

    val retryingClient = retry andThen client

    watchState.foreach(_.recordApiCall(req))
    Trace.letClear(retryingClient(req)).flatMap {
      case rep if rep.status == http.Status.NotFound => Future.value(None)
      case rep => Api.parse[G](rep).map { g =>
        watchState.foreach(_.recordResponse(g))
        Some(g)
      }
    }
  }

  /**
   * implementing classes should define this method to retrieve the current state
   * of the resource to pass as Modified results to any watchers.
   *
   * @return a Future containing a sequence of Watches and an optional String representing the
   *         current resourceVersion
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
   * This function has semantics similar to the argument of [[Var.async]], and can be used to create
   * a stream of [[Watch]] objects containing additions, modifications, and deletions to the items
   * in the list being watched,
   *
   * @return A Closable handle allowing for the watch to be terminated.
   */
  def watch(
    labelSelector: Option[String] = None,
    fieldSelector: Option[String] = None,
    resourceVersion: Option[String] = None,
    state: Updatable[Activity.State[W]],
    watchState: Option[WatchState[G, W]] = None
  ): Closable = {
    val close = new AtomicReference[Closable](Closable.nop)

    // Internal method used to recursively retry watches as needed on failures.
    def _watch(resourceVersion: Option[String], backoffs0: Backoff): Future[Unit] = {
      val req = Api.mkreq(http.Method.Get, watchPath, None,
        LabelSelectorKey -> labelSelector,
        FieldSelectorKey -> fieldSelector,
        ResourceVersionKey -> resourceVersion)
      val retryingClient = infiniteRetryFilter andThen client
      watchState.foreach(_.recordApiCall(req))
      val initialState = Trace.letClear(retryingClient(req))

      close.set(Closable.make { _ =>
        log.trace("k8s watch cancelled")
        initialState.raise(Api.Closed)
        Future.Unit
      })

      initialState.flatMap { rsp =>
        rsp.status match {
          // NOTE: 5xx-class statuses will be retried by the infiniteRetryFilter above.
          case http.Status.Ok =>
            watchState.foreach(_.recordStreamStart())
            close.set(Closable.make { _ =>
              log.debug("k8s watch closed")
              watchState.foreach(_.recordStreamEnd())
              Future {
                rsp.reader.discard()
              } handle {
                case _: ReaderDiscardedException =>
              }
            })

            _processEventStream(
              () => Json.readStream[W](rsp.reader),
              resourceVersion
            )

          case status =>
            rsp.reader.discard()
            log.warning("k8s failed to watch resource %s: %d %s", path, status.code, status.reason)
            Future.sleep(backoffs0.duration).before(_resourceVersionTooOld(backoffs0.next))
        }
      }
    }

    /**
     * Recursively consume an AsyncStream of watch events from an established connection,
     * updating [[state]] when appropriate.  Restart the watch on various error conditions.
     */
    def _processEventStream(
      stream: () => AsyncStream[W],
      largestVersion: Option[String],
      largestEvent: Option[W] = None
    ): Future[Unit] = {
      stream().uncons.flatMap {
        // Special case to handle Kubernetes bug where "too old resource version" errors are
        // returned with status code 200 rather than status code 410.
        // see https://github.com/kubernetes/kubernetes/issues/35068 for details
        case Some((e: Watch.Error[O], _)) if e.status.code.contains(410) =>
          log.debug(
            "k8s returned 'too old resource version' error with incorrect HTTP status code, " +
              "restarting watch"
          )
          _resourceVersionTooOld(backoffs)

        case Some((event, ws)) =>
          import Ordering.Implicits._
          watchState.foreach(_.recordStreamData(event))
          // Register the update only if its resource version is larger than or equal to the largest version
          // seen so far.
          if (largestEvent.forall(_ <= event)) {
            log.trace(
              "k8s watch on '%s' registered event with same or newer resource version %s (older resource version was %s)",
              watchPath,
              event.resourceVersion,
              largestEvent.flatMap(_.resourceVersion)
            )
            state.update(Activity.Ok(event))
            _processEventStream(ws, event.resourceVersion, Some(event))
          } else {
            log.trace(
              "k8s watch on '%s' skipping event with resource version %s (older than the most recent resource version %s)",
              watchPath,
              event.resourceVersion,
              largestEvent.flatMap(_.resourceVersion)
            )
            _processEventStream(ws, largestVersion, largestEvent)
          }

        // If the stream ends (k8s will kill connections after ~30m), restart it at the last known
        // resource version.
        case None if largestVersion.isDefined =>
          _watch(largestVersion, backoffs)

        // No known resource version: load the initial information before watching again.
        case None =>
          _resourceVersionTooOld(backoffs)
      }
    }

    def _resourceVersionTooOld(backoffs0: Backoff): Future[Unit] = {
      // Gone is returned by k8s to indicate the requested resource version is too old to watch.
      // A common scenario that exhibits this error is:
      //
      // 1. Kubernetes kills a connection or the connection is lost
      // 2. We try to re-establish the watch from the last received version, but because we are only
      //    watching a subset of resources, that version was a while ago
      // 3. The watch fails
      //
      // We need to reload the initial information to ensure we are "caught up," and then watch
      // again.
      log.debug("k8s restarting watch on %s, resource version %s was too old", watchPath,
        resourceVersion)
      watchState.foreach(_.recordStreamEnd())
      restartWatches(labelSelector, fieldSelector).flatMap {
        case (ws, ver) =>
          for (w <- ws) {
            watchState.foreach(_.recordStreamData(w))
            state.update(Activity.Ok(w))
          }

          _watch(ver, backoffs0)
      }
    }

    val _ = _watch(resourceVersion, backoffs)
    Closable.ref(close)
  }

  /**
   * Convert this Watchable into an [[Activity]] with a function called on
   * each watch event to update the state of the [[Activity]].
   * @param convert       function called to convert the response of a GET request on
   *                      this resource initial state of the [[Activity]]. this takes an
   *                      [[Option]] in case the GET request returns 404.
   * @param labelSelector an optional label selector field for the Kubernetes API
   *                      request.
   * @param fieldSelector an optional field selector field for the Kubernetes API
   *                      request.
   * @param onEvent       function called on each [[Watch]] event.
   * @return an [[Activity]]`[T]` updated by [[Watch]] events on this object,
   *         where `T` is the return type of the `onEvent` function
   */
  def activity[T](
    convert: Option[G] => T,
    labelSelector: Option[String] = None,
    fieldSelector: Option[String] = None,
    watchState: Option[WatchState[G, W]] = None
  )(onEvent: (T, W) => T): InstrumentedActivity[T] =
    InstrumentedActivity { state =>
      val closeRef = new AtomicReference[Closable](Closable.nop)
      val pending = get(
        labelSelector = labelSelector,
        fieldSelector = fieldSelector,
        retryIndefinitely = true,
        watchState = watchState
      )
        // since we're retrying the GET request forever, this `onFailure`
        // should probably never fire. but who knows?
        .onFailure { e =>
          log.warning(s"k8s failed to get resource at $path: $e")
          state.update(Activity.Failed(e))
        }
        // otherwise, update the activity with the initial state, and
        // apply the onEvent function to each successive watch event in
        // the stream.
        .onSuccess { initial =>
          val initialState = convert(initial)
          state.update(Activity.Ok(initialState))

          val version = if (watchResourceVersion) {
            for {
              initialValue <- initial
              metadata <- initialValue.metadata
              resourceVersion <- metadata.resourceVersion
            } yield resourceVersion
          } else {
            None
          }

          val witness = Var
            .async[Activity.State[W]](Activity.Pending) { updatable =>
              watch(labelSelector, fieldSelector, version, updatable, watchState)
            }
            .changes
            .foldLeft(initialState) {
              case (state, activity) =>
                activity match {
                  case Activity.Ok(event) => onEvent(state, event)
                  case _ => state
                }
            }.register(note => state.update(Activity.Ok(note)))
          closeRef.set(witness)
        }

      Closable.make { t =>
        pending.raise(Closed)
        Closable.ref(closeRef).close(t)
      }

    }
}

object Watchable {
  def DefaultBackoff = Backoff.exponentialJittered(1.milliseconds, 5.seconds)
  private object Closed extends Throwable

  private[k8s] val LabelSelectorKey = "labelSelector"
  private[k8s] val FieldSelectorKey = "fieldSelector"
  private[k8s] val ResourceVersionKey = "resourceVersion"
}
