package io.l5d.clientTls

import com.fasterxml.jackson.core.JsonParser
import com.twitter.finagle.Stack
import com.twitter.finagle.Stack.Params
import com.twitter.finagle.buoyant.TlsClientPrep
import io.buoyant.linkerd.{Parsing, TlsClientInitializer}
import io.l5d.clientTls.static.{CaCert, CommonName}

object static {
  val defaultParams = Stack.Params.empty

  case class CommonName(name: String)
  implicit object CommonName extends Stack.Param[CommonName] {
    override def default = CommonName("")
  }

  case class CaCert(path: Option[String])
  implicit object CaCert extends Stack.Param[CaCert] {
    override def default = CaCert(None)
  }

  val commonName = Parsing.Param.Text("commonName") { name =>
    CommonName(name)
  }

  val caCert = Parsing.Param.Text("caCertPath") { path =>
    CaCert(Some(path))
  }

  val parser = Parsing.Params(commonName, caCert)
}

class static(val params: Stack.Params) extends TlsClientInitializer {
  def this() = this(static.defaultParams)

  override def withParams(ps: Params) = new static(ps)

  override def paramKeys: Set[String] = static.parser.keys

  override def tlsClientPrep[Req, Rsp] = {
    val CommonName(name) = params[CommonName]
    val CaCert(caCert) = params[CaCert]
    TlsClientPrep.static[Req, Rsp](name, caCert)
  }

  override def readParam(key: String, p: JsonParser): TlsClientInitializer =
    withParams(static.parser.read(key, p, params))
}
