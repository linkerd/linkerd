package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.finagle.{Dtab, Path}
import com.twitter.util.Future
import io.buoyant.config.types.Port
import io.buoyant.k8s.istio.{ClusterCache, IstioIdentifierBase, RouteCache}
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.HttpIdentifierConfig
import io.buoyant.linkerd.protocol.http.ErrorResponder.HttpResponseException
import io.buoyant.router.RoutingFactory.{IdentifiedRequest, Identifier, RequestIdentification}
import istio.proxy.v1.config.HTTPRedirect

class IstioIdentifier(val pfx: Path, baseDtab: () => Dtab, val routeCache: RouteCache, val clusterCache: ClusterCache)
  extends Identifier[Request] with IstioIdentifierBase[Request] {

  override def apply(req: Request): Future[RequestIdentification[Request]] = {
    req.host match {
      case Some(host) =>
        getIdentifiedPath(req).map { path =>
          val dst = Dst.Path(path, baseDtab(), Dtab.local)
          new IdentifiedRequest(dst, req)
        }
      case None => throw new IllegalArgumentException("no host found for request")
    }
  }

  def reqToMeta(req: Request): IstioRequestMeta =
    //TODO: match on request scheme
    IstioRequestMeta(req.path, "", req.method.toString, req.host.getOrElse(""), req.headerMap.get)

  def redirectRequest(redir: HTTPRedirect, req: Request): Future[Nothing] = {
    val redirect = Response(Status.Found)
    redirect.location = redir.`uri`.getOrElse(req.uri)
    redirect.host = redir.`authority`.orElse(req.host).getOrElse("")
    Future.exception(HttpResponseException(redirect))
  }

  def rewriteRequest(uri: String, authority: Option[String], req: Request): Unit = {
    req.uri = uri
    req.host = authority.getOrElse("")
  }
}

case class IstioIdentifierConfig(
  discoveryHost: Option[String],
  discoveryPort: Option[Port],
  apiserverHost: Option[String],
  apiserverPort: Option[Port]
) extends HttpIdentifierConfig {

  override def newIdentifier(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base
  ): Identifier[Request] = {
    import io.buoyant.k8s.istio._

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
