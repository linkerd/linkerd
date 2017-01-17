package io.buoyant.linkerd.clientTls

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Path, ServiceFactory, Stack, Stackable}
import com.twitter.finagle.buoyant.TlsClientPrep
import com.twitter.finagle.client.AddrMetadataExtraction.AddrMetadata
import com.twitter.finagle.ssl.Engine
import com.twitter.finagle.transport.TlsConfig
import com.twitter.logging.Logger
import io.buoyant.config.Parser
import io.buoyant.linkerd.{TlsClientConfig, TlsClientInitializer}
import io.buoyant.namer.util.PathMatcher
import java.net.SocketAddress

class BoundPathInitializer extends TlsClientInitializer {
  val configClass = classOf[BoundPathConfig]
  override def configId = "io.l5d.boundPath"
}

object BoundPathInitializer extends BoundPathInitializer

case class BoundPathConfig(
  caCertPath: Option[String],
  names: Seq[NameMatcherConfig],
  strict: Option[Boolean]
) extends TlsClientConfig {

  private[clientTls] def peerCommonName(params: Stack.Params) =
    params[AddrMetadata].metadata("id") match {
      case id: String => commonNameFromPath(Path.read(id))
      case _ => None
    }

  private[this] def commonNameFromPath(path: Path): Option[String] =
    names.map { n =>
      n.matcher.substitute(path, n.commonNamePattern)
    }.collectFirst {
      case Some(result) => result
    } match {
      case None if strict.getOrElse(true) =>
        val n = names.map(_.prefix).mkString(",")
        throw new MatcherError(s"Unable to match ${path.show} with available names: $n")
      case default => default
    }

  @JsonIgnore
  override def tlsClientPrep[Req, Rsp]: Stackable[ServiceFactory[Req, Rsp]] =
    new Stack.Module[ServiceFactory[Req, Rsp]] {
      override val role = TlsClientPrep.role
      override val description = TlsClientPrep.description
      override val parameters: Seq[Stack.Param[_]] = Seq(AddrMetadata.param)

      private val log = Logger.get(getClass.getName)

      private[this] def tlsify(params: Stack.Params): TlsClientPrep.Params =
        peerCommonName(params) match {
          case Some(cn) =>
            val certs = caCertPath.toSeq.map(TlsClientPrep.loadCert(_))
            val trust = TlsClientPrep.Trust.Verified(cn, certs)
            Stack.Params.empty +
              TlsClientPrep.TransportSecurity(TlsClientPrep.TransportSecurity.Secure()) +
              TlsClientPrep.Trust(trust)

          case None =>
            Stack.Params.empty +
              TlsClientPrep.TransportSecurity(TlsClientPrep.TransportSecurity.Insecure)
        }

      override def make(params: Stack.Params, next: Stack[ServiceFactory[Req, Rsp]]) =
        Stack.Leaf(role, next.make(params ++ tlsify(params)))
    }
}

class MatcherError(msg: String) extends Throwable(msg)

case class NameMatcherConfig(prefix: String, commonNamePattern: String) {
  def matcher: PathMatcher = PathMatcher(prefix)
}
