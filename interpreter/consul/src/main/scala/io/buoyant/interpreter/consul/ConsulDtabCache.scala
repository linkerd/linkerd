package io.buoyant.interpreter.consul

import com.google.common.cache.{CacheBuilder, CacheLoader}
import com.twitter.finagle.util.DefaultTimer
import com.twitter.finagle.{Dtab, Failure, Path}
import com.twitter.io.Buf
import com.twitter.logging.Logger
import com.twitter.util._
import io.buoyant.consul.v1.InstrumentedApiCall.mkPollState
import io.buoyant.consul.v1._
import io.buoyant.namer.InstrumentedVar

class ConsulDtabCache(
  api: KvApi,
  root: Path,
  datacenter: Option[String] = None,
  readConsistency: Option[ConsistencyMode] = None,
  writeConsistency: Option[ConsistencyMode] = None,
  implicit val _timer: Timer = DefaultTimer
) {

  private[this] val log = Logger.get("ConsulInterpreter")

  private[this] val dtabCache = CacheBuilder.newBuilder()
    .build[String, Activity[Option[Dtab]]](
      new CacheLoader[String, Activity[Option[Dtab]]] {
        override def load(key: String): Activity[Option[Dtab]] = _observe(key)
      }
    )

  def observe(ns: String): Activity[Option[Dtab]] = dtabCache.get(ns).stabilize

  private[this] def _observe(ns: String): Activity[Option[Dtab]] = {
    val key = s"${root.show}/$ns"
    val pollState = mkPollState[Indexed[String]]
    val run = InstrumentedVar[Activity.State[Option[Dtab]]](Activity.Pending) { updates =>
      @volatile var running = true

      def cycle(index: Option[String], backoffs0: Stream[Duration]): Future[Unit] =
        if (running) {
          val apiCall = api.get(
            key,
            blockingIndex = index,
            datacenter = datacenter,
            retry = true,
            consistency = readConsistency
          )
          InstrumentedApiCall.execute(apiCall, pollState)
            .transform {
              case Return(result) =>
                val version = Buf.Utf8(result.index.get)
                // the raw string, not yet parsed as a dtab.
                val rawDtab = result.value
                // attempt to parse the string as a dtab, and update the the
                // Activity with  the new state - either Ok if the string was
                // parsed successfully, or Failed if an error occurred.
                val nextState = Try {
                  Dtab.read(rawDtab)
                } match {
                  case Return(dtab) => // dtab parsing succeeded.
                    Activity.Ok(Some(dtab))
                  case Throw(e) => // dtab parsing failed!
                    log.error("consul ns %s dtab parsing failed: %s; dtab: '%s'", ns, e, rawDtab)
                    Activity.Failed(e)
                }
                updates() = nextState
                cycle(result.index, backoffs0)

              case Throw(e: NotFound) =>
                updates() = Activity.Ok(None)
                cycle(e.rsp.headerMap.get(Headers.Index), backoffs0)
              case Throw(e: Failure) if e.isFlagged(Failure.Interrupted) => Future.Done
              case Throw(e) =>
                updates() = Activity.Failed(e)
                log.error("consul ns %s dtab observation error %s", ns, e)
                val sleep #:: backoffs1 = backoffs0
                Future.sleep(sleep).before(cycle(None, backoffs1))

            }
        } else
          Future.Unit
      val pending = cycle(None, api.backoffs)

      Closable.make { _ =>
        running = false
        pending.raise(Failure("Consul observation released", Failure.Interrupted))
        Future.Unit
      }
    }
    Activity(run.underlying).stabilize
  }
}
