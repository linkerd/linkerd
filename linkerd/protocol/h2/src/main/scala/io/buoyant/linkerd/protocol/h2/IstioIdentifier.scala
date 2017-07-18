package io.buoyant.linkerd.protocol.h2

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.buoyant.h2._
import com.twitter.finagle.{Dtab, Path, Stack}
import com.twitter.util.Future
import io.buoyant.config.types.Port
import io.buoyant.k8s.istio.{ClusterCache, IstioIdentifierBase, RouteCache}
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.H2IdentifierConfig
import io.buoyant.linkerd.protocol.h2.ErrorReseter.H2ResponseException
import io.buoyant.router.RoutingFactory.{BaseDtab, DstPrefix, IdentifiedRequest, Identifier, RequestIdentification}
import istio.proxy.v1.config.HTTPRedirect

class IstioIdentifier(val pfx: Path, baseDtab: () => Dtab, val routeCache: RouteCache, val clusterCache: ClusterCache)
  extends Identifier[Request] with IstioIdentifierBase[Request] {

  override def apply(req: Request): Future[RequestIdentification[Request]] = {
    getIdentifiedPath(req).map { path =>
      val dst = Dst.Path(path, baseDtab(), Dtab.local)
      new IdentifiedRequest(dst, req)
    }
  }

  def redirectRequest(redir: HTTPRedirect, req: Request): Future[Nothing] = {
    val resp = Response(Status.Found, Stream.empty())
    resp.headers.set(Headers.Path, redir.`uri`.getOrElse(req.path))
    resp.headers.set(Headers.Authority, redir.`authority`.getOrElse(req.authority))
    Future.exception(H2ResponseException(resp))
  }

  def rewriteRequest(uri: String, authority: Option[String], req: Request): Unit = {
    req.headers.set(Headers.Path, uri)
    req.headers.set(Headers.Authority, authority.getOrElse(""))
  }

  def reqToMeta(req: Request): IstioRequestMeta =
    IstioRequestMeta(req.path, req.scheme, req.method.toString, req.authority, req.headers.get)
}

case class IstioIdentifierConfig(
  discoveryHost: Option[String],
  discoveryPort: Option[Port],
  apiserverHost: Option[String],
  apiserverPort: Option[Port]
) extends H2IdentifierConfig {

  @JsonIgnore
  override def newIdentifier(params: Stack.Params) = {
    import io.buoyant.k8s.istio._

    val DstPrefix(prefix) = params[DstPrefix]
    val BaseDtab(baseDtab) = params[BaseDtab]
    val host = apiserverHost.getOrElse(DefaultApiserverHost)
    val port = apiserverPort.map(_.port).getOrElse(DefaultApiserverPort)
    val routeCache = RouteCache.getManagerFor(host, port)
    val discoveryClient = DiscoveryClient(
      discoveryHost.getOrElse(DefaultDiscoveryHost),
      discoveryPort.map(_.port).getOrElse(DefaultDiscoveryPort)
    )
    val clusterCache = new ClusterCache(discoveryClient)
    new IstioIdentifier(prefix, baseDtab, routeCache, clusterCache)
  }
}

object IstioIdentifierConfig {
  val kind = "io.l5d.k8s.istio"
}

class IstioIdentifierInitializer extends IdentifierInitializer {
  val configClass = classOf[IstioIdentifierConfig]
  override val configId = IstioIdentifierConfig.kind
}

object IstioIdentifierInitializer extends IstioIdentifierInitializer
