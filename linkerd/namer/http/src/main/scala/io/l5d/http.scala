package io.l5d

import com.fasterxml.jackson.core.{JsonParser, JsonToken}
import com.twitter.finagle.{Path, Stack}
import io.buoyant.linkerd.{NamerInitializer, Parsing}
import io.buoyant.linkerd.namer.http.HttpNamer

object http {

  case class DropVersion(drop: Boolean)
  implicit object DropVersion extends Stack.Param[DropVersion] {
    val default = DropVersion(false)
    val parser = Parsing.Param.Boolean("dropVersion")(DropVersion(_))
  }

  case class DropMethod(drop: Boolean)
  implicit object DropMethod extends Stack.Param[DropMethod] {
    val default = DropMethod(false)
    val parser = Parsing.Param.Boolean("dropMethod")(DropMethod(_))
  }

  case class DropHost(drop: Boolean)
  implicit object DropHost extends Stack.Param[DropHost] {
    val default = DropHost(false)
    val parser = Parsing.Param.Boolean("dropHost")(DropHost(_))
  }

  case class DstPrefix(path: Option[Path])
  implicit object DstPrefix extends Stack.Param[DstPrefix] {
    val default = DstPrefix(None)
    val parser = Parsing.Param.Text("dstPrefix") { text =>
      DstPrefix(Some(Path.read(text)))
    }
  }

  val parser = Parsing.Params(
    DropVersion.parser,
    DropMethod.parser,
    DropHost.parser,
    DstPrefix.parser
  )

  val defaultParams = Stack.Params.empty +
    NamerInitializer.Prefix(Path.Utf8("io.l5d.http"))
}

class http(val params: Stack.Params) extends NamerInitializer {
  def this() = this(http.defaultParams)
  def withParams(ps: Stack.Params) = new http(ps)

  def paramKeys = http.parser.keys
  def readParam(k: String, p: JsonParser) =
    withParams(http.parser.read(k, p, params))

  def newNamer() = params[http.DstPrefix] match {
    case http.DstPrefix(None) =>
      throw new IllegalArgumentException("dstPrefix must be set on io.l5d.http")

    case http.DstPrefix(Some(dstPrefix)) =>
      val http.DropVersion(dropVersion) = params[http.DropVersion]
      val http.DropMethod(dropMethod) = params[http.DropMethod]
      val http.DropHost(dropHost) = params[http.DropHost]
      new HttpNamer(dstPrefix, dropVersion, dropMethod, dropHost)
  }
}
