package io.buoyant.k8s

import com.twitter.finagle.{Filter, Http, Stack}
import com.twitter.finagle.http.{Request, Response}
import io.buoyant.k8s
import scala.io.Source

/**
 * Meant to be implemented by configuration classes that need to
 * produce a k8s API client.
 */
trait ClientConfig {
  import ClientConfig._

  def host: Option[String]
  def portNum: Option[Int]
  def tls: Option[Boolean]
  def tlsWithoutValidation: Option[Boolean]
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

  protected def mkClient(
    params: Stack.Params = Stack.Params.empty
  ) = {
    val setHost = new SetHostFilter(getHost, getPort)
    val client = (tls, tlsWithoutValidation) match {
      case (Some(false), Some(_)) =>
        log.warning("tlsWithoutValidation is specified, but has no effect as tls is disabled")
        Http.client
      case (Some(false), _) => Http.client
      case (_, Some(true)) => Http.client.withTlsWithoutValidation
      case _ => Http.client.withTls(setHost.host)
    }
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
}
