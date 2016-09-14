package io.buoyant.linkerd

import com.twitter.finagle.Path
import com.twitter.util.{Await, Awaitable, Closable, CloseAwaitably, Future, Return, Throw, Time}
import io.buoyant.admin.{AdminInitializer, App}
import io.buoyant.linkerd.admin.LinkerdAdmin
import java.io.File
import scala.io.Source

/**
 * linkerd main execution.
 *
 * usage: linkerd path/to/config
 *
 * The config file may be either JSON- or YAML-formatted
 */
object Main extends App {

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

  private def loadLinker(path: String): Linker.LinkerConfig = {
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

  private def initAdmin(
    linker: Linker,
    config: Linker.LinkerConfig
  ): Closable with Awaitable[Unit] = {
    val linkerdAdmin = new LinkerdAdmin(this, linker, config)
    log.info(s"serving http admin on %s", linker.admin.port.port)
    AdminInitializer.run(linker.admin, linkerdAdmin.adminMuxer)
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
    announcers0: Seq[(Path, Announcer)],
    server: Server.Initializer,
    name: Path
  ): Closable = {
    announcers0.filter { case (pfx, _) => name.startsWith(pfx) } match {
      case Nil =>
        log.warning("no announcer found for %s", name.show)
        Closable.nop

      case announcers =>
        val closers = announcers.map {
          case (prefix, announcer) =>
            log.info("announcing %s as %s to %s", server.addr, name.show, announcer.scheme)
            announcer.announce(server.addr, name.drop(prefix.size))
        }
        Closable.all(closers: _*)
    }
  }

}
