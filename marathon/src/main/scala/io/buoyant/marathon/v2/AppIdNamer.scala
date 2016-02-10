package io.buoyant.marathon.v2

import com.twitter.finagle.{Addr, Name, Namer, NameTree, Path}
import com.twitter.finagle.util.DefaultTimer
import com.twitter.finagle.tracing.Trace
import com.twitter.util._
import java.net.SocketAddress

object AppIdNamer {
  object Closed extends Throwable
  val log = Api.log
}

class AppIdNamer(
  api: Api,
  prefix: Path,
  ttl: Duration,
  timer: Timer = DefaultTimer.twitter
) extends Namer {

  import AppIdNamer._

  private[this] implicit val _timer = timer

  /**
   * Accepts names in the form:
   *   /<app-id>/residual/path
   *
   * and attempts to bind an Addr by resolving named endpoint from the
   * Marathon master.
   */
  def lookup(path: Path): Activity[NameTree[Name]] = path match {
    case Path.Utf8(app, _*) =>
      val id = prefix ++ Path.Utf8(app)
      val residual = path.drop(1)
      // Marathon appends a leading '/' to app names that don't have it.
      val appId =
        if (app startsWith "/") app
        else s"/$app"
      Trace.recordBinary("marathon.appId", appId)
      Trace.recordBinary("marathon.id", id.show)
      Trace.recordBinary("marathon.path", path.show)
      appsActivity.map {
        case apps if apps(appId) =>
          Trace.recordBinary("marathon.found", appId)
          val addr = getAndMonitorAddr(appId)
          NameTree.Leaf(Name.Bound(addr, id, residual))
        case apps =>
          Trace.recordBinary("marathon.notfound", appId)
          NameTree.Neg
      }

    case _ => Activity.value(NameTree.Neg)
  }

  private[this] val appsActivity: Activity[Api.AppIds] = {
    val states = Var.async[Activity.State[Api.AppIds]](Activity.Pending) { state =>
      def loop(): Future[Unit] =
        api.getAppIds().transform {
          case Return(apps) =>
            state() = Activity.Ok(apps)
            Future.sleep(ttl).before(loop())

          case Throw(NonFatal(e)) =>
            state() = Activity.Failed(e)
            Future.sleep(ttl).before(loop())

          case Throw(e) =>
            state() = Activity.Failed(e)
            Future.exception(e)
        }

      val work = loop()
      Closable.make { deadline =>
        work.raise(Closed)
        Future.Unit
      }
    }
    Activity(states)
  }

  private[this] var appMonitors: Map[String, Var[Addr]] = Map.empty
  private[this] def getAndMonitorAddr(app: String): Var[Addr] = synchronized {
    appMonitors.get(app) match {
      case Some(addr) => addr

      case None =>
        val addr = Var.async[Addr](Addr.Pending) { addr =>
          def loop(): Future[Unit] =
            api.getAddrs(app).transform { ret =>
              ret match {
                case Return(addrs) =>
                  addr() = Addr.Bound(addrs)
                  Future.sleep(ttl).before(loop())

                case Throw(NonFatal(e)) =>
                  addr() = Addr.Failed(e)
                  Future.sleep(ttl).before(loop())

                case Throw(e) =>
                  addr() = Addr.Failed(e)
                  Future.exception(e)
              }
            }

          val work = loop()
          Closable.make { deadline =>
            synchronized {
              appMonitors -= app
            }
            work.raise(Closed)
            Future.Unit
          }
        }
        appMonitors += (app -> addr)

        addr
    }
  }
}
