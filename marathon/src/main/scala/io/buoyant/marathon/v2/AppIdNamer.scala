package io.buoyant.marathon.v2

import com.twitter.finagle._
import com.twitter.finagle.tracing.Trace
import com.twitter.finagle.util.DefaultTimer
import com.twitter.util.{NonFatal => _, _}
import scala.util.control.NonFatal

object AppIdNamer {
  object Closed extends Throwable
}

class AppIdNamer(
  api: Api,
  prefix: Path,
  ttl: Duration,
  timer: Timer = DefaultTimer
) extends Namer {

  import AppIdNamer._

  private[this] implicit val _timer = timer

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
      appsActivity.map { apps =>
        Trace.recordBinary("marathon.path", path.show)
        val found = possibleIds.collectFirst {
          case app if apps(app) =>
            Trace.recordBinary("marathon.appId", app.show)
            val residual = path.drop(app.size)
            val id = prefix ++ app
            val addr = getAndMonitorAddr(app)
            Name.Bound(addr, id, residual)
        }
        Trace.recordBinary("marathon.found", found.isDefined)
        found match {
          case Some(name) => NameTree.Leaf(name)
          case None => NameTree.Neg
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
        val addr = Var.async[Addr](Addr.Pending) { addr =>
          @volatile var initialized, stopped = false
          @volatile var pending: Future[_] = Future.never

          def loop(): Unit = if (!stopped) {
            pending = api.getAddrs(app).respond {
              case Return(addrs) =>
                initialized = true
                addr() = Addr.Bound(addrs)
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

        appMonitors += (app -> addr)
        addr
    }
  }
}
