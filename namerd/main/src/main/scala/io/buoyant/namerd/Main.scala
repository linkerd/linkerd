package io.buoyant.namerd

import com.twitter.util.{Await, Closable, Duration}
import io.buoyant.admin.{App, Build}
import java.io.File
import scala.io.Source
import sun.misc.{Signal, SignalHandler}

object Main extends App {

  /**
   * Flag to validate a configuration.
   */
  private val validate = flag.apply("validate", false, "Only validate a configuration and finish with a proper status code")
  private[this] val DefaultShutdownGrace =
    Duration.fromSeconds(10)

  private[this] var shutdownGracePeriod: Option[Int] = null

  def main(): Unit = {
    val build = Build.load("/io/buoyant/namerd/build.properties")
    log.info("namerd %s (rev=%s) built at %s", build.version, build.revision, build.name)

    args match {
      case Array(path) =>
        val config = loadNamerd(path)
        if (validate())
          return

        val namerd = config.mk()
        shutdownGracePeriod = config.admin.flatMap(_.shutdownGraceMs)

        val admin = namerd.admin.serve(this, NamerdAdmin(config, namerd))
        log.info("serving %s on %s", namerd.admin.scheme, admin.boundAddress)

        val servers = namerd.interfaces.map { iface =>
          val server = iface.serve()
          log.info("serving %s interface on %s", iface.kind, server.boundAddress)
          server
        }
        val telemeters = namerd.telemeters.map(_.run())
        registerTerminationSignalHandler(shutdownGracePeriod)
        closeOnExit(Closable.sequence(
          Closable.all(servers: _*),
          Closable.all(telemeters: _*),
          admin
        ))
        Await.all(servers: _*)
        Await.all(telemeters: _*)
        Await.result(admin)

      case _ => exitOnError("usage: namerd path/to/config")
    }
  }

  private def loadNamerd(path: String): NamerdConfig = {
    val configText = path match {
      case "-" =>
        Source.fromInputStream(System.in).mkString
      case path =>
        val f = new File(path)
        if (!f.isFile) throw new IllegalArgumentException(s"config is not a file: $path")
        Source.fromFile(f).mkString
    }

    NamerdConfig.loadNamerd(configText)
  }

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

  override def defaultCloseGracePeriod: Duration =
    shutdownGracePeriod
      .map(Duration.fromMilliseconds(_))
      .getOrElse(DefaultShutdownGrace)
}
