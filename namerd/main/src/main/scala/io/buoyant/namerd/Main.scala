package io.buoyant.namerd

import com.twitter.util.Await
import io.buoyant.config.types.Port
import java.io.File
import scala.io.Source
import io.buoyant.admin.{AdminConfig, AdminInitializer, App}

object Main extends App {

  def main(): Unit = {
    args match {
      case Array(path) =>
        val config = loadNamerd(path)
        val namerd = config.mk(statsReceiver)

        val admin = new NamerdAdmin(this, config, namerd)
        val adminInitializer = new AdminInitializer(
          config.admin.getOrElse(AdminConfig(Port(9991))),
          admin.adminMuxer
        )
        adminInitializer.startServer()
        closeOnExit(adminInitializer.adminHttpServer)

        val servers = namerd.interfaces.map { iface =>
          val server = iface.serve()
          closeOnExit(server)
          log.info(s"serving ${iface.kind} interface on ${server.boundAddress}")
          server
        }

        Await.all(servers: _*)

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
