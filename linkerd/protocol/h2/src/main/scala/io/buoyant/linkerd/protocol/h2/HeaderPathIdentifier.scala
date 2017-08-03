package io.buoyant.linkerd
package protocol
package h2

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Dtab, Path, Stack}
import com.twitter.finagle.buoyant.{Dst, ParamsMaybeWith}
import com.twitter.finagle.buoyant.h2.{Request, Headers => H2Headers}
import com.twitter.util.Future
import io.buoyant.router.H2
import io.buoyant.router.RoutingFactory._

class HeaderPathIdentifier(
  header: String,
  segments: Option[Int],
  pfx: Path,
  baseDtab: () => Dtab
) extends Identifier[Request] {
  import HeaderPathIdentifier._

  private[this] val identifyPath: Request => Future[RequestIdentification[Request]] =
    segments match {
      case Some(segments) if segments < 1 =>
        throw new IllegalArgumentException("segments must be >= 1")

      case Some(segments) =>
        lazy val tooShort = {
          val msg = s"Path must have at least $segments segment(s)"
          Future.value(new UnidentifiedRequest[Request](msg))
        }
        (req: Request) => reqPath(req) match {
          case p if p.size < segments => tooShort
          case p => identified(p.take(segments), req)
        }

      case None =>
        lazy val noPath = {
          val msg = s"Missing destination path"
          Future.value(new UnidentifiedRequest[Request](msg))
        }
        (req: Request) => reqPath(req) match {
          case Path.empty => noPath
          case p => identified(p, req)
        }
    }

  private[this] def reqPath(req: Request): Path =
    req.headers.get(header) match {
      case Some(UriPath(p)) if p.nonEmpty => Path.read(p)
      case _ => Path.empty
    }

  private[this] def identified(p: Path, req: Request) = {
    val dst = Dst.Path(pfx ++ p, baseDtab(), Dtab.local)
    Future.value(new IdentifiedRequest(dst, req))
  }

  override def apply(req: Request): Future[RequestIdentification[Request]] =
    identifyPath(req)
}

object HeaderPathIdentifier {

  case class Header(name: String)
  implicit object Header extends Stack.Param[Header] {
    val default = Header(H2Headers.Path)
  }

  case class Segments(segments: Option[Int])
  implicit object Segments extends Stack.Param[Segments] {
    val default = Segments(None)
  }

  def mk(params: Stack.Params) = {
    val Header(header) = params[Header]
    val Segments(segments) = params[Segments]
    val DstPrefix(pfx) = params[DstPrefix]
    val BaseDtab(baseDtab) = params[BaseDtab]
    new HeaderPathIdentifier(header, segments, pfx, baseDtab)
  }

  val param = H2.Identifier(mk)

  private object UriPath {
    def unapply(uri: String): Option[String] =
      uri.indexOf('?') match {
        case -1 => Some(uri.stripSuffix("/"))
        case idx => Some(uri.substring(0, idx).stripSuffix("/"))
      }
  }
}

class HeaderPathIdentifierConfig extends H2IdentifierConfig {
  var header: Option[String] = None
  var segments: Option[Int] = None

  @JsonIgnore
  override def newIdentifier(params: Stack.Params) =
    segments match {
      case Some(segments) if (segments < 1) =>
        throw new IllegalArgumentException("`segments` must be >= 1")
      case segments =>
        val prms = params
          .maybeWith(header.map { h => HeaderPathIdentifier.Header(h.toLowerCase) })
          .maybeWith(segments.map { n => HeaderPathIdentifier.Segments(Some(n)) })
        HeaderPathIdentifier.mk(prms)
    }
}

class HeaderPathIdentifierInitializer extends IdentifierInitializer {
  val configClass = classOf[HeaderPathIdentifierConfig]
  override val configId = "io.l5d.header.path"
}

object HeaderPathIdentifierInitializer extends HeaderPathIdentifierInitializer
