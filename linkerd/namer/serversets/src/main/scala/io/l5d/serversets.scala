package io.l5d

import com.fasterxml.jackson.core.{JsonParser, JsonToken}
import com.twitter.finagle.{Path, Stack}
import io.buoyant.linkerd.namer.serversets.ServersetNamer
import io.buoyant.linkerd.{NamerInitializer, Parsing}
import io.l5d.serversets.ZkConnectString

object serversets {

  private[this] case class PartialZkAddr(hostname: Option[String] = None, port: Option[Int] = Some(2181)) {
    def zkAddr: String = {
      (hostname, port) match {
        case (Some(h), Some(p)) => s"$h:$p"
        case (None, _) => throw new IllegalArgumentException("zkAddrs must contain a host parameter")
        case (_, None) => throw new IllegalArgumentException("zkAddrs must contain a port parameter")
      }
    }
  }

  /**
   * A zookeeper 'connect string' that is used to build the underlying client.
   */
  case class ZkConnectString(connectString: String)
  implicit object ZkConnectString extends Stack.Param[ZkConnectString] {
    val default = ZkConnectString("")

    val param = Parsing.Param("zkAddrs") { (json, params) =>
      val addrs = Parsing.ensureTok(json, JsonToken.START_ARRAY) { json =>
        Parsing.foldArray(json, Seq.empty[PartialZkAddr]) { (zkAddrs, json) =>
          val zkAddr = Parsing.foldObject(json, PartialZkAddr()) {
            case (partialZkAddr, "host", json) =>
              Parsing.ensureTok(json, JsonToken.VALUE_STRING) { json =>
                val hostname = json.getText
                json.nextToken()
                partialZkAddr.copy(hostname = Some(hostname))
              }
            case (partialZkAddr, "port", json) =>
              Parsing.ensureTok(json, JsonToken.VALUE_NUMBER_INT) { json =>
                val port = json.getIntValue
                json.nextToken()
                partialZkAddr.copy(port = Some(port))
              }
          }
          zkAddrs :+ zkAddr
        }
      }
      params + ZkConnectString(addrs.map(_.zkAddr).mkString(","))
    }
  }

  val parser = Parsing.Params(ZkConnectString.param)

  val defaultParams = Stack.Params.empty +
    NamerInitializer.Prefix(Path.Utf8("io.l5d.serversets"))
}

class serversets(val params: Stack.Params) extends NamerInitializer {

  def this() = this(serversets.defaultParams)
  def withParams(ps: Stack.Params) = new serversets(ps)

  def paramKeys = serversets.parser.keys
  def readParam(k: String, p: JsonParser) =
    withParams(serversets.parser.read(k, p, params))

  def newNamer() = {
    val zkAddrs = params[ZkConnectString]
    if (zkAddrs.connectString.nonEmpty)
      new ServersetNamer(zkAddrs.connectString)
    else
      throw new IllegalArgumentException("io.l5d.serversets requires a 'zkAddrs'")
  }
}
