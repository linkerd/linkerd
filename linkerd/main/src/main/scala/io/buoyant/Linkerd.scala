package io.buoyant

import com.twitter.util.Await
import io.buoyant.linkerd.Linker.LinkerConfig
import io.buoyant.linkerd.admin.{AdminInitializer, LinkerdAdmin}
import io.buoyant.linkerd.{Build, Linker}
import java.io.File
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
        val linkerConfig = loadLinker(path)
        val linker = linkerConfig.mk

        val linkerdAdmin = new LinkerdAdmin(this, linker, linkerConfig)
        val adminInitializer = new AdminInitializer(linker.admin, linkerdAdmin.adminMuxer)
        adminInitializer.startServer()
        closeOnExit(adminInitializer.adminHttpServer)

        // TODO initialize:
        // - namers
        // - tracers
        val routers = linker.routers.flatMap { router =>
          val running = router.initialize()
          closeOnExit(running)
          running.servers.map { server =>
            log.info("serving %s on %s:%d", server.router, server.ip, server.port)
            val listening = server.serve()
            closeOnExit(listening)
            listening
          }
        }
        Await.all(routers: _*)

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
}
