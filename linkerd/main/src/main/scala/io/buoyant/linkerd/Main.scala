package io.buoyant.linkerd

import com.twitter.finagle.Path
import com.twitter.util._
import io.buoyant.admin.{App, Build}
import io.buoyant.linkerd.admin.LinkerdAdmin
import java.io.File
import java.net.{InetSocketAddress, NetworkInterface}

import io.netty.handler.ssl.OpenSsl

import scala.collection.JavaConverters._
import scala.io.Source
import sun.misc.{Signal, SignalHandler}

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
        val config = loadLinker(path)
        reportOpenSSLVersion(config)

        val linker = config.mk()
        val admin = initAdmin(config, linker)
        val telemeters = linker.telemeters.map(_.run())
        val routers = linker.routers.map(initRouter(_))
        val closableNamers = linker.namers.map(_._2).collect { case x: Closable with Awaitable[_] => x }

        log.info("initialized")
        registerTerminationSignalHandler(config.admin.flatMap(_.shutdownGraceMs))
        closeOnExit(Closable.sequence(
          Closable.all(routers: _*),
          Closable.all(telemeters: _*),
          Closable.all(closableNamers: _*),
          admin
        ))
        Await.all(routers: _*)
        Await.all(telemeters: _*)
        Await.all(closableNamers: _*)
        Await.result(admin)

      case _ => exitOnError("usage: linkerd path/to/config")
    }
  }

  // Just logs the availability of tcnative ssl binding, for troubleshooting
  private def reportOpenSSLVersion(config: Linker.LinkerConfig): Unit = {
    if (config.routers.exists(_.servers.exists(_.tls.isDefined))) {
      log.info(s"TLS required, looking for tcnative binding ...")
      try {
        if (OpenSsl.isAvailable) {
          val version = OpenSsl.versionString()
          val ciphers = OpenSsl.availableOpenSslCipherSuites()
          log.info(s"Native OpenSSL available! version: $version, ciphers: $ciphers")
        } else {
          log.warning(OpenSsl.unavailabilityCause(), "No native OpenSSL available")
        }
      } catch {
        case ex: Exception => {
          log.warning(ex, "No native OpenSSL available")
        }
      }
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
    config: Linker.LinkerConfig,
    linker: Linker
  ): Closable with Awaitable[Unit] = {
    val server = linker.admin.serve(this, LinkerdAdmin(config, linker))
    log.info(s"serving http admin on %s", server.boundAddress)
    server
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
      private[this] val closer = Closable.sequence(Closable.all(servers: _*), router, Closable.all(router.announcers.map(_._2): _*))
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
      val a = for {
        interface <- NetworkInterface.getNetworkInterfaces.asScala
        if interface.isUp
        inet <- interface.getInetAddresses.asScala
        if !inet.isLoopbackAddress
      } yield new InetSocketAddress(inet.getHostAddress, server.port)
      a.toSeq
    } else {
      Seq(server.addr)
    }

    announcers0.filter { case (pfx, _) => name.startsWith(pfx) } match {
      case Nil =>
        log.warning("no announcer found for %s", name.show)
        Closable.nop

      case announcers =>
        val closers = announcers.flatMap {
          case (prefix, announcer) =>
            for {
              addr <- addrs
            } yield {
              log.info("announcing %s as %s to %s", addr, name.show, announcer.scheme)
              announcer.announce(addr, name.drop(prefix.size))
            }
        }
        Closable.all(closers: _*)
    }
  }

  /**
   * Trap termination signals and triggers an App.close for a graceful shutdown.
   * Shutdown hook is not used because it has, at least, the following problems:
   * <ul>
   *   <li>LogManager uses a shutdown hook which makes nothing to be logged during shutdown
   *   <li>TracerCache uses a shutdown hook to flush
   * </ul>
   */
  private def registerTerminationSignalHandler(shutdownGraceMs: Option[Int]): Unit = {
    val shutdownHandler = new SignalHandler {
      override def handle(sig: Signal): Unit = {
        log.info("Received %s. Shutting down ...", sig)
        val closeTimeOut = shutdownGraceMs.map(Duration.fromMilliseconds(_)).getOrElse(DefaultShutdownGrace)
        Await.result(close(closeTimeOut))
      }
    }

    Signal.handle(new Signal("INT"), shutdownHandler)
    val _ = Signal.handle(new Signal("TERM"), shutdownHandler)
  }

}
