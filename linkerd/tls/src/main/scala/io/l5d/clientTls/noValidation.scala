package io.l5d.clientTls

import com.fasterxml.jackson.core.JsonParser
import com.twitter.finagle.Stack
import com.twitter.finagle.Stack.Params
import com.twitter.finagle.buoyant.TlsClientPrep
import io.buoyant.linkerd.TlsClientInitializer

object noValidation {
  val defaultParams = Stack.Params.empty
}

class noValidation(val params: Stack.Params) extends TlsClientInitializer {
  def this() = this(noValidation.defaultParams)

  override def withParams(ps: Params) = new noValidation(ps)

  override def paramKeys: Set[String] = Set.empty

  override def tlsClientPrep[Req, Rsp] = TlsClientPrep.withoutCertificateValidation[Req, Rsp]

  override def readParam(key: String, p: JsonParser): TlsClientInitializer = this
}
