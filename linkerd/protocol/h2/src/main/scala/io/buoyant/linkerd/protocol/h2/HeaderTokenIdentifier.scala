package io.buoyant.linkerd
package protocol
package h2

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Dtab, Path, Stack}
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.buoyant.h2.{Headers, Request}
import com.twitter.finagle.buoyant.ParamsMaybeWith
import com.twitter.util.Future
import io.buoyant.router.H2
import io.buoyant.router.RoutingFactory._

object HeaderTokenIdentifier {

  case class Header(key: String)
  implicit object Header extends Stack.Param[Header] {
    val default = Header(Headers.Authority)
  }

  def mk(params: Stack.Params): Identifier[Request] = {
    val Header(header) = params[Header]
    val DstPrefix(pfx) = params[DstPrefix]
    val BaseDtab(baseDtab) = params[BaseDtab]
    new HeaderTokenIdentifier(header, pfx, baseDtab)
  }

  val param = H2.Identifier(mk)
}

class HeaderTokenIdentifier(header: String, pfx: Path, baseDtab: () => Dtab)
  extends Identifier[Request] {

  private[this] val unidentified: RequestIdentification[Request] =
    new UnidentifiedRequest(s"missing header: '$header'")

  override def apply(req: Request): Future[RequestIdentification[Request]] =
    req.headers.get(header) match {
      case None | Some("") => Future.value(unidentified)
      case Some(token) =>
        val path = pfx ++ Path.Utf8(token)
        val dst = Dst.Path(path, baseDtab(), Dtab.local)
        Future.value(new IdentifiedRequest(dst, req))
    }
}

class HeaderTokenIdentifierConfig extends H2IdentifierConfig {
  var header: Option[String] = None

  @JsonIgnore
  override def newIdentifier(params: Stack.Params) =
    HeaderTokenIdentifier.mk(params
      .maybeWith(header.map(HeaderTokenIdentifier.Header(_))))
}

object HeaderTokenIdentifierConfig {
  val kind = "io.l5d.header.token"
}

class HeaderTokenIdentifierInitializer extends IdentifierInitializer {
  val configClass = classOf[HeaderTokenIdentifierConfig]
  override val configId = HeaderTokenIdentifierConfig.kind
}

object HeaderTokenIdentifierInitializer extends HeaderTokenIdentifierInitializer
