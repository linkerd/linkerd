package io.buoyant.marathon.v2

import com.twitter.finagle.{Addr, Name, Namer, NameTree, Path}
import com.twitter.finagle.util.DefaultTimer
import com.twitter.util._
import java.net.SocketAddress

object Api {
  type Apps = Set[String]
}

trait Api {
  def getApps(): Future[Api.Apps]
  def getAddrs(app: String): Future[Set[SocketAddress]]
}

/**
 * XXX this isn't particularly smart or efficient
 */
class AppIdNamer(
  api: Api,
  prefix: Path,
  ttl: Duration,
  timer: Timer = DefaultTimer.twitter
) extends Namer {
  private[this] implicit val _timer = timer

  def lookup(path: Path): Activity[NameTree[Name]] = path match {
    case Path.Utf8(app, _*) =>
      val id = prefix ++ Path.Utf8(app)
      val residual = path.drop(1)
      appsActivity.map {
        case apps if apps(app) =>
          val addr = watchAddr(app)
          NameTree.Leaf(Name.Bound(addr, id, residual))

        case _ => NameTree.Neg
      }

    case _ => Activity.value(NameTree.Neg)
  }

  // XXX chaining futures indefinitely may be ungood?
  private[this] val appsActivity: Activity[Api.Apps] = {
    val states = Var.async[Activity.State[Api.Apps]](Activity.Pending) { state =>
      def loop(): Future[Unit] =
        api.getApps().transform {
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
        work.raise(AppIdNamer.Closed)
        Future.Unit
      }
    }
    Activity(states)
  }

  // XXX could this be lockless?
  private[this] var appMonitors: Map[String, Var[Addr]] = Map.empty
  private[this] def watchAddr(app: String): Var[Addr] = synchronized {
    appMonitors.get(app) match {
      case Some(addr) => addr

      case None =>
        // XXX chaining futures indefinitely may be ungood?
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
            work.raise(AppIdNamer.Closed)
            Future.Unit
          }
        }
        appMonitors += (app -> addr)

        addr
    }
  }
}

object AppIdNamer {
  object Closed extends Throwable

  private[AppIdNamer] class AppMonitor(
    app: String,
    api: Api,
    ttl: Duration,
    timer: Timer = DefaultTimer.twitter
  ) {
    private[this] implicit val _timer = timer

  }
}
