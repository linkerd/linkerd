package io.l5d.clientTls

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.Path
import com.twitter.finagle.Stack.{Param, Params}
import com.twitter.finagle.buoyant.TlsClientPrep
import com.twitter.finagle.buoyant.TlsClientPrep.Module
import com.twitter.finagle.client.AddrMetadataExtraction.AddrMetadata
import com.twitter.finagle.ssl.Engine
import com.twitter.logging.Logger
import io.buoyant.linkerd.config.Parser
import io.buoyant.linkerd.util.PathMatcher
import io.buoyant.linkerd.{TlsClientConfig, TlsClientInitializer}
import java.net.SocketAddress

class boundPath extends TlsClientInitializer {
  val configClass = classOf[BoundPathConfig]
  val configId = "io.l5d.clientTls.boundPath"
}

case class BoundPathConfig(caCertPath: Option[String], names: Seq[NameMatcherConfig]) extends TlsClientConfig {
  @JsonIgnore
  override def tlsClientPrep[Req, Rsp]: Module[Req, Rsp] = {

    def commonNameFromPath(path: Path): Option[String] =
      names.map { n =>
        n.matcher.substitute(path, n.commonNamePattern)
      }.collectFirst {
        case Some(result) => result
      }

    new TlsClientPrep.Module[Req, Rsp] {
      private val log = Logger.get(getClass.getName)

      override def newEngine(params: Params): Option[(SocketAddress) => Engine] =
        peerCommonName(params).map(TlsClientPrep.addrEngine(_, caCertPath))

      override def peerCommonName(params: Params): Option[String] = {
        for {
          path <- params[AddrMetadata].metadata("id") match {
            case id: String => Some(Path.read(id))
            case _ => None
          }
          commonName <- commonNameFromPath(path)
        } yield {
          log.info(s"Using $commonName as the TLS common name for ${path.show}")
          commonName
        }
      }

      override def parameters: Seq[Param[_]] = Seq(AddrMetadata.param)
    }
  }
}

case class NameMatcherConfig(prefix: String, commonNamePattern: String) {
  def matcher: PathMatcher = PathMatcher(prefix)
}
