package io.buoyant.namerd

import com.twitter.util.{Await, Closable}
import io.buoyant.admin.App
import io.buoyant.telemetry.CommonMetricsTelemeter
import java.io.File
import scala.io.Source

object Main extends App {

  private[this] val DefaultTelemeter = new CommonMetricsTelemeter

  def main(): Unit = {
    args match {
      case Array(path) =>
        val config = loadNamerd(path)
        val namerd = config.mk(DefaultTelemeter)

        val admin = namerd.admin.serve(this, NamerdAdmin(config, namerd))
        log.info(s"serving http admin on ${admin.boundAddress}")

        val servers = namerd.interfaces.map { iface =>
          val server = iface.serve()
          log.info(s"serving ${iface.kind} interface on ${server.boundAddress}")
          server
        }

        closeOnExit(Closable.sequence(
          Closable.all(servers: _*),
          admin
        ))
        Await.all(servers: _*)
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
