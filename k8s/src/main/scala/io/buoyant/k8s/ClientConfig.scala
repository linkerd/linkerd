package io.buoyant.k8s

import com.twitter.finagle.tracing.NullTracer
import com.twitter.finagle.{Http, Stack}

/**
 * Meant to be implemented by configuration classes that need to
 * produce a k8s API client.
 */
trait ClientConfig {

  protected val DefaultHost = "localhost"
  protected val DefaultNamespace = "default"
  protected val DefaultPort = 8001

  def host: Option[String]
  def portNum: Option[Int]

  protected def getHost = host.getOrElse(DefaultHost)

  protected def getPort = portNum.getOrElse(DefaultPort)

  protected def dst = s"/$$/inet/$getHost/$getPort"

  protected def mkClient(
    params: Stack.Params = Stack.Params.empty
  ) = {
    val setHost = new SetHostFilter(getHost, getPort)
    Http.client.withParams(Http.client.params ++ params)
      .withTracer(NullTracer)
      .withStreaming(true)
      .filtered(setHost)
  }
}
