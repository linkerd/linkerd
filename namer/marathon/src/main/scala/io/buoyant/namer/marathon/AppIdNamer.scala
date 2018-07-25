package io.buoyant.namer.marathon

import com.fasterxml.jackson.databind.SerializationFeature
import com.twitter.finagle._
import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.finagle.tracing.Trace
import com.twitter.finagle.util.DefaultTimer
import com.twitter.util._
import io.buoyant.admin.Admin
import io.buoyant.config.Parser
import io.buoyant.marathon.v2.{Api, WatchState}
import io.buoyant.namer.InstrumentedVar
import scala.util.control.NonFatal

object AppIdNamer {
  object Closed extends Throwable
}

class AppIdNamer(
  api: Api,
  prefix: Path,
  ttl: => Duration,
  timer: Timer = DefaultTimer
) extends Namer with Admin.WithHandlers {

  import AppIdNamer._

  private[this] case class InstrumentedMarathonWatch(
    instrumentedAddrs: InstrumentedVar[Addr],
    marathonWatchState: WatchState
  )
  private[this] implicit val _timer = timer
  private[this] lazy val handlerPrefix = prefix.drop(1).show.drop(1)
  private[this] var appToAddrMap: Map[Path, InstrumentedMarathonWatch] = Map.empty

  /**
   * Accepts names in the form:
   *   /<app-id>/residual/path
   *
   *   or
   *
   *   /<group-id>/<app-id>/residual/path
   *
   *   etc
   *
   * and attempts to bind an Addr by resolving named endpoint from the
   * Marathon master.
   */
  def lookup(path: Path): Activity[NameTree[Name]] =
    if (path.isEmpty) Activity.value(NameTree.Neg)
    else {
      val Path.Utf8(segs@_*) = path
      val lowercasePath = Path.Utf8(segs.map(_.toLowerCase): _*)
      // each time the map of all Apps updates, find the
      // shortest-matching part of `path` that exists as an App ID.
      val possibleIds = (1 to lowercasePath.size).map(lowercasePath.take(_))
      appsActivity.flatMap { apps =>
        Trace.recordBinary("marathon.path", path.show)
        val found = possibleIds.collectFirst {
          case app if apps(app) =>
            Trace.recordBinary("marathon.appId", app.show)
            val residual = path.drop(app.size)
            val id = prefix ++ app
            val addr = getAndMonitorAddr(app)

            val observation = addr.map {
              case Addr.Neg => Activity.Ok(NameTree.Neg)
              case Addr.Pending => Activity.Pending
              case Addr.Failed(why) => Activity.Failed(why)
              case Addr.Bound(_, _) => Activity.Ok(NameTree.Leaf(Name.Bound(addr, id, residual)))
            }
            Activity(observation)
        }

        Trace.recordBinary("marathon.found", found.isDefined)
        found match {
          case Some(nameTree) => nameTree
          case None => Activity.value(NameTree.Neg)
        }
      }
    }

  private[this] val appsActivity: Activity[Api.AppIds] =
    Activity(Var.async[Activity.State[Api.AppIds]](Activity.Pending) { state =>
      @volatile var initialized, stopped = false
      @volatile var pending: Future[_] = Future.never

      def loop(): Unit = if (!stopped) {
        pending = api.getAppIds().respond {
          case Return(apps) =>
            initialized = true
            state() = Activity.Ok(apps)
            Trace.recordBinary("marathon.apps", apps.map(_.show).mkString(","))
            if (!stopped) {
              pending = Future.sleep(ttl).onSuccess(_ => loop())
            }

          case Throw(NonFatal(e)) =>
            if (!initialized) {
              state() = Activity.Failed(e)
            }
            if (!stopped) {
              pending = Future.sleep(ttl).onSuccess(_ => loop())
            }

          case Throw(e) =>
            state() = Activity.Failed(e)
        }
      }

      loop()
      Closable.make { deadline =>
        stopped = true
        pending.raise(Closed)
        Future.Unit
      }
    })

  private[this] var appMonitors: Map[Path, Var[Addr]] = Map.empty

  private[this] def getAndMonitorAddr(app: Path): Var[Addr] = synchronized {
    appMonitors.get(app) match {
      case Some(addr) => addr

      case None =>
        val watch = new WatchState()
        val addr = InstrumentedVar[Addr](Addr.Pending) { addr =>
          @volatile var initialized, stopped = false
          @volatile var pending: Future[_] = Future.never

          def loop(): Unit = if (!stopped) {
            pending = api.getAddrs(app, Some(watch)).respond {
              case Return(addrs) =>
                initialized = true
                addr() = if (addrs.isEmpty) Addr.Neg else Addr.Bound(addrs)
                if (!stopped) {
                  pending = Future.sleep(ttl).onSuccess(_ => loop())
                }

              case Throw(NonFatal(e)) =>
                if (!initialized) {
                  addr() = Addr.Failed(e)
                }
                if (!stopped) {
                  pending = Future.sleep(ttl).onSuccess(_ => loop())
                }

              case Throw(e) =>
                addr() = Addr.Failed(e)
            }
          }

          loop()
          Closable.make { deadline =>
            stopped = true
            synchronized {
              appMonitors -= app
            }
            pending.raise(Closed)
            Future.Unit
          }
        }
        appToAddrMap += (app -> InstrumentedMarathonWatch(addr, watch))
        appMonitors += (app -> addr.underlying)
        addr.underlying
    }
  }

  override def adminHandlers: Seq[Admin.Handler] = {
    Seq(Admin.Handler(
      s"namer_state/$handlerPrefix.json",
      new MarathonNamerHandler
    ))
  }

  private[AppIdNamer] class MarathonNamerHandler extends Service[Request, Response] {
    private[this] val mapper = Parser.jsonObjectMapper(Nil)
      .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)

    override def apply(request: Request): Future[Response] = {
      val currentState = appToAddrMap.map {
        case (path, marathonAddr) => Map(
          s"${path.show}" -> Map(
            "state" -> marathonAddr.instrumentedAddrs.stateSnapshot(),
            "watch" -> marathonAddr.marathonWatchState
          )
        )
      }
      val json = mapper.writeValueAsString(currentState)
      val rep = Response(request.version, Status.Ok)
      rep.contentString = json
      Future.value(rep)
    }
  }
}

