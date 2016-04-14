package io.buoyant.k8s

import com.twitter.finagle.{Filter, Http, Stack}
import com.twitter.finagle.http.{Request, Response}
import scala.io.Source

/**
 * Meant to be implemented by configuration classes that need to
 * produce a k8s API client.
 */
trait ClientConfig {
  import ClientConfig._

  def host: Option[String]
  def portNum: Option[Int]
  def tls: Option[TlsClientConfig]
  def authTokenFile: Option[String]

  protected def getHost = host.getOrElse(DefaultHost)

  protected def getPort = portNum.getOrElse(DefaultPort)

  protected def dst = s"/$$/inet/$getHost/$getPort"

  private def authFilter = authTokenFile match {
    case Some(path) =>
      val token = Source.fromFile(path).mkString
      if (token.nonEmpty) new AuthFilter(token)
      else Filter.identity[Request, Response]
    case None => Filter.identity[Request, Response]
  }

  private[this] val tlsPrepRole = Stack.Role("TlsClientPrep")

  protected def mkClient(
    params: Stack.Params = Stack.Params.empty
  ) = {
    val setHost = new SetHostFilter(getHost, getPort)
    val module = tls.getOrElse(DefaultTlsConfig).tlsClientPrep(getHost)

    val client = Http.Client(Http.Client.stack.replace(tlsPrepRole, module))

    client.withParams(client.params ++ params)
      .withStreaming(true)
      .filtered(setHost)
      .filtered(authFilter)
  }

}

object ClientConfig {
  val DefaultHost = "kubernetes.default.svc.cluster.local"
  val DefaultNamespace = "default"
  val DefaultPort = 443
  val DefaultTlsConfig = new TlsClientConfig(None, None, None)
}

