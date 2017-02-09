package io.buoyant.namerd

import com.twitter.finagle.Dtab
import com.twitter.finagle.serverset2.ZkClient
import com.twitter.io.Buf
import com.twitter.util.Await
import io.buoyant.admin.App
import io.buoyant.namerd.storage.ZkConfig
import java.io.File
import scala.io.Source

object DcosBootstrap extends App {

  val defaultNs = "default"
  val defaultDtab = Dtab.read(
    """|/marathonId => /#/io.l5d.marathon ;
       |/svc => /$/io.buoyant.http.domainToPathPfx/marathonId ;
       |""".stripMargin
  )

  def main(): Unit = {
    args match {
      case Array(path) =>
        val config = loadNamerd(path)
        config.storage match {
          case zkStorage: ZkConfig => {
            // TODO: consider sharing with ZkDtabStoreInitializer
            val zkClient = new ZkClient(
              zkStorage.zkAddrs.map(_.toString).mkString(","),
              zkStorage.pathPrefix.getOrElse("/dtabs")
            )
            closeOnExit(zkClient)
            Await.result(zkClient.create(defaultNs, Buf.Utf8(defaultDtab.show)))
          }
          case storage => exitOnError(s"config file does not specify zk storage: ${storage}")
        }

      case _ => exitOnError("usage: namerd-dcos-bootstrap path/to/config")
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
