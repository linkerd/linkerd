package io.buoyant.namerd

import com.twitter.util.{Await, Closable}
import io.buoyant.admin.{App, Build}
import java.io.File
import scala.io.Source

object Main extends App {

  def main(): Unit = {
    val build = Build.load("/io/buoyant/namerd/build.properties")
    log.info("namerd %s (rev=%s) built at %s", build.version, build.revision, build.name)

    args match {
      case Array(path) =>
        val config = loadNamerd(path)
        val namerd = config.mk()

        val admin = namerd.admin.serve(this, NamerdAdmin(config, namerd))
        log.info(s"serving ${namerd.admin.scheme} on ${admin.boundAddress}")

        val servers = namerd.interfaces.map { iface =>
          val server = iface.serve()
          log.info(s"serving ${iface.kind} interface on ${server.boundAddress}")
          server
        }
        val telemeters = namerd.telemeters.map(_.run())

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

}
