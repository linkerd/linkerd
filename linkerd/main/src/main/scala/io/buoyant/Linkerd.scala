package io.buoyant

import com.twitter.util.{Await, Closable, CloseAwaitably, Time}
import io.buoyant.admin.{AdminInitializer, App}
import io.buoyant.linkerd.Linker.LinkerConfig
import io.buoyant.linkerd.admin.LinkerdAdmin
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

        val telemeters = linker.telemeters.map(_.run())

        val routers = linker.routers.map { routerConfig =>
          val router = routerConfig.initialize()

          val servers = router.servers.map { server =>
            log.info("serving %s on %s:%d", server.router, server.ip, server.port)
            val listening = server.serve()

            // TODO we should add closing semantics to announcers so
            // they can gracefully teardown with servers.
            for (name <- server.announce) {
              val announcers = router.announcers.filter {
                case (prefix, _) => name.startsWith(prefix)
              }

              for ((prefix, announcer) <- announcers) {
                log.info("announcing %s as %s to %s", server.addr, name.show, announcer.scheme)
                announcer.announce(server.addr, name.drop(prefix.size)).onSuccess(closeOnExit)
              }
              if (announcers.isEmpty) log.warning("no announcer found for %s", name.show)
            }

            listening
          }

          new Closable with CloseAwaitably {
            def close(deadline: Time) = closeAwaitably {
              val serving = servers.map { server =>
                Closable.make { d =>
                  log.debug("closing %s server: %s", routerConfig.label, server.boundAddress)
                  server.close(d)
                }
              }

              Closable.all(serving: _*).close(deadline).before {
                log.debug("closing router: %s", routerConfig.label)
                router.close(deadline)
              }
            }
          }
        }

        closeOnExit(Closable.make { deadline =>
          Closable.all(routers: _*).close(deadline).before {
            val telems = Closable.all(telemeters: _*)
            Closable.sequence(telems, adminInitializer.adminHttpServer).close(deadline)
          }
        })

        Await.all(routers: _*)
        Await.all(telemeters: _*)
        Await.result(adminInitializer.adminHttpServer)

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
