package io.buoyant

import com.twitter.finagle.{Failure, Path}
import com.twitter.util.{Await, Awaitable, Closable, CloseAwaitably, Future, Return, Throw, Time}
import io.buoyant.admin.{AdminInitializer, App}
import io.buoyant.linkerd.Linker.LinkerConfig
import io.buoyant.linkerd.admin.LinkerdAdmin
import io.buoyant.linkerd.{Announcer, Build, Linker, Router, Server}
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import scala.io.Source

/**
 * linkerd main execution.
 *
 * usage: linkerd path/to/config
 *
 * The config file may be either JSON- or YAML-formatted
 */
object Linkerd extends App {

  def main() {
    val build = Build.load(getClass.getResourceAsStream("/io/buoyant/linkerd-main/build.properties"))
    log.info("linkerd %s (rev=%s) built at %s", build.version, build.revision, build.name)

    args match {
      case Array(path) =>
        val config = loadLinker(path)
        val linker = config.mk
        val admin = initAdmin(linker, config)
        val telemeters = linker.telemeters.map(_.run())
        val routers = linker.routers.map(initRouter(_))
        closeOnExit(Closable.sequence(
          Closable.all(routers: _*),
          Closable.all(telemeters: _*),
          admin
        ))
        Await.all(routers: _*)
        Await.all(telemeters: _*)
        Await.result(admin)

      case _ => exitOnError("usage: linkerd path/to/config")
    }
  }

  private def loadLinker(path: String): LinkerConfig = {
    val configText = path match {
      case "-" =>
        Source.fromInputStream(System.in).mkString
      case path =>
        val f = new File(path)
        if (!f.isFile) throw new IllegalArgumentException(s"config is not a file: $path")
        Source.fromFile(f).mkString
    }
    Linker.parse(configText)
  }

  private def initAdmin(linker: Linker, linkerConfig: LinkerConfig): Closable with Awaitable[Unit] = {
    val linkerdAdmin = new LinkerdAdmin(this, linker, linkerConfig)
    val adminInitializer = new AdminInitializer(linker.admin, linkerdAdmin.adminMuxer)
    adminInitializer.startServer()
    adminInitializer.adminHttpServer
  }

  private def initRouter(config: Router): Closable with Awaitable[Unit] = {
    val router = config.initialize()

    val servers = router.servers.map { server =>
      log.info("serving %s on %s:%d", server.router, server.ip, server.port)
      val listening = server.serve()
      val announcements = announce(router.announcers, server)
      Closable.sequence(announcements, listening)
    }

    new Closable with CloseAwaitably {
      private[this] val closer = Closable.sequence(Closable.all(servers: _*), router)
      def close(deadline: Time) = closeAwaitably { closer.close(deadline) }
    }
  }

  private def announce(announcers: Seq[(Path, Announcer)], server: Server.Initializer): Closable =
    Closable.all(server.announce.map(announce(announcers, server, _)): _*)

  private def announce(
    announcers: Seq[(Path, Announcer)],
    server: Server.Initializer,
    name: Path
  ): Closable =
    announcers.filter { case (pfx, _) => name.startsWith(pfx) } match {
      case Seq() =>
        log.warning("no announcer found for %s", name.show)
        Closable.nop

      case announcers =>
        val closers = announcers.map {
          case (prefix, announcer) => announce(prefix, announcer, server, name)
        }
        Closable.all(closers: _*)
    }

  private def announce(
    prefix: Path,
    announcer: Announcer,
    server: Server.Initializer,
    name: Path
  ): Closable = {
    log.info("announcing %s as %s to %s", server.addr, name.show, announcer.scheme)
    val f = announcer.announce(server.addr, name.drop(prefix.size))

    // If we close before the announcer registers, we
    // cancel the registration. If we close after
    // registration, we close it.
    //
    // XXX we should change the announcer API to return
    // an Announcement synchronously (that may actually
    // wrap some asynchronous announcement logic).
    val closeRef = new AtomicReference[Closable](Closable.make { _ =>
      f.raise(Failure("closed").flagged(Failure.Interrupted))
      Future.Unit
    })
    f.respond {
      case Throw(e) => closeRef.set(Closable.nop)
      case Return(closer) => closeRef.set(closer)
    }
    Closable.ref(closeRef)
  }

}
