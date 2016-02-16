package io.l5d.clientTls

import com.fasterxml.jackson.core.{JsonParser, JsonToken}
import com.twitter.finagle.Stack.{Param, Params}
import com.twitter.finagle.buoyant.TlsClientPrep
import com.twitter.finagle.client.AddrMetadataExtraction.AddrMetadata
import com.twitter.finagle.ssl.Engine
import com.twitter.finagle.{Path, Stack}
import com.twitter.logging.Logger
import io.buoyant.linkerd.util.PathMatcher
import io.buoyant.linkerd.{Parsing, TlsClientInitializer}
import io.l5d.clientTls.boundPath.{CaCert, NameMatcher, NameMatchers}
import java.net.SocketAddress

object boundPath {
  val defaultParams = Stack.Params.empty

  case class CaCert(path: Option[String])
  implicit object CaCert extends Stack.Param[CaCert] {
    override def default = CaCert(None)
  }

  case class NameMatcher(matcher: PathMatcher, pattern: String)
  implicit object NameMatchers extends Stack.Param[Seq[NameMatcher]] {
    override def default = Seq.empty
  }

  val caCert = Parsing.Param.Text("caCertPath") { path =>
    CaCert(Some(path))
  }

  val nameMatchers = Parsing.Param("names") { (json, params) =>
    params + Parsing.foldArray(json, Seq.empty[NameMatcher]) { (matchers, json) =>
      val matcher = Parsing.foldObject(json, NameMatcher(null, "")) {
        case (nameMatcher, "prefix", json) =>
          Parsing.ensureTok(json, JsonToken.VALUE_STRING) { json =>
            val m = nameMatcher.copy(matcher = PathMatcher(json.getText))
            json.nextToken()
            m
          }
        case (nameMatcher, "commonNamePattern", json) =>
          Parsing.ensureTok(json, JsonToken.VALUE_STRING) { json =>
            val m = nameMatcher.copy(pattern = json.getText)
            json.nextToken()
            m
          }
        case (nameMatcher, key, json) =>
          throw Parsing.error(s"TLS name has unexpected parameter: $key", json)
      }
      if (matcher.matcher == null) throw Parsing.error("TLS name entry must contain a prefix", json)
      if (matcher.pattern == "") throw Parsing.error("TLS name entry must contain a commonNamePattern", json)
      matchers :+ matcher
    }
  }

  val parser = Parsing.Params(caCert, nameMatchers)
}

class boundPath(val params: Stack.Params) extends TlsClientInitializer {
  def this() = this(boundPath.defaultParams)

  private val log = Logger.get(getClass.getName)

  override def withParams(ps: Params) = new boundPath(ps)

  override def paramKeys: Set[String] = boundPath.parser.keys

  override def tlsClientPrep[Req, Rsp] = {
    val CaCert(caCert) = params[CaCert]
    val nameMatchers = params[Seq[NameMatcher]]

    def commonNameFromPath(path: Path): Option[String] =
      nameMatchers.map {
        case NameMatcher(matcher, pattern) =>
          matcher.substitute(path, pattern)
      }.collectFirst {
        case Some(result) => result
      }

    new TlsClientPrep.Module[Req, Rsp] {
      override def newEngine(params: Params): Option[(SocketAddress) => Engine] =
        peerCommonName(params).map(TlsClientPrep.addrEngine(_, caCert))

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

  override def readParam(key: String, p: JsonParser): TlsClientInitializer =
    withParams(boundPath.parser.read(key, p, params))
}
