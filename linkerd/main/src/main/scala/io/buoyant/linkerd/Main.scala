package io.buoyant.linkerd

import java.io.File

import com.twitter.finagle.Path
import com.twitter.util._
import io.buoyant.admin.{AdminConfig, App, Build}
import io.buoyant.linkerd.admin.LinkerdAdmin

import scala.io.Source

/**
 * linkerd main execution.
 *
 * usage: linkerd path/to/config
 *
 * The config file may be either JSON- or YAML-formatted
 */
object Main extends App {

  private[this] val DefaultShutdownGrace =
    Duration.fromSeconds(10)

  def main() {
    val build = Build.load("/io/buoyant/linkerd/build.properties")
    log.info("linkerd %s (rev=%s) built at %s", build.version, build.revision, build.name)

    args match {
      case Array(path) =>
        start(loadLinker(path))(TerminationHook) match {
          case Throw(ex) => exitOnError(ex.getMessage)
          case Return(svc) =>
            closeOnExit(svc)
            Await.result(svc)
        }
      case _ => exitOnError("usage: linkerd path/to/config")
    }

  }

  private val shutdownGraceMs =
    (config: AdminConfig) =>
      config.shutdownGraceMs
        .map(Duration.fromMilliseconds(_))
        .getOrElse(DefaultShutdownGrace)

  private val shutdown = (shutdownGraceMs: Duration) => {
    Await.result[Unit](close(shutdownGraceMs))
  }

  def start(config: Linker.LinkerConfig)(terminationHook: TerminationHook): Try[Closable with CloseAwaitably] = {
    val maybeSvcs = for {
      linker <- Try(config.mk())
      admin = initAdmin(config, linker)
      telemeters = linker.telemeters.map(_.run())
      routers = linker.routers.map(initRouter(_))
      _ = log.info("initialized")
    } yield admin ++ telemeters ++ routers

    for {
      svcs <- maybeSvcs
      _ = terminationHook.register(shutdown, config.admin.map(shutdownGraceMs).get)
    } yield new Closable with CloseAwaitably {
      private[this] val closer = Closable.sequence(Closable.all(svcs: _*))
      def close(deadline: Time) = closeAwaitably { closer.close(deadline) }
    }

  }

  def loadLinker(path: String): Linker.LinkerConfig = {
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
    config: Linker.LinkerConfig,
    linker: Linker
  ): Seq[Closable with Awaitable[Unit]] = {
    val server = linker.admin.serve(this, LinkerdAdmin(config, linker))
    log.info("serving %s admin on %s", linker.admin.scheme, server.boundAddress)

    config.admin.flatMap(_.httpIdentifierPort) match {
      case Some(p) =>
        val idServer = linker.admin.serveHandler(p.port, LinkerdAdmin.identifierHandler(config, linker))
        log.info("serving http identifier on %s", idServer.boundAddress)
        Seq(server, idServer)
      case None => Seq(server)
    }
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
    val addrs = if (server.ip.getHostAddress == "0.0.0.0") {
      InetSocketAddresses.listeningOn(server.port)
    } else {
      Seq(server.addr)
    }

    announcers0.filter { case (pfx, _) => name.startsWith(pfx) } match {
      case Nil =>
        log.warning("no announcer found for %s", name.show)
        Closable.nop

      case announcers =>
        val closers = for {
          (prefix, announcer) <- announcers
          addr <- addrs
          _ = log.info("announcing %s as %s to %s", addr, name.show, announcer.scheme)
        } yield announcer.announce(addr, name.drop(prefix.size))

        Closable.all(closers: _*)
    }
  }

}
