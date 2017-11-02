package io.buoyant.k8s.istio.identifiers

import com.twitter.finagle.{Dtab, Path}
import com.twitter.util.{Activity, Future, Time}
import io.buoyant.grpc.runtime.GrpcStatus
import io.buoyant.k8s.istio._
import io.buoyant.k8s.istio.mixer.{MixerCheckStatus, MixerClient}
import io.buoyant.router.RoutingFactory.IdentifiedRequest
import io.buoyant.test.FunSuite
import istio.proxy.v1.config.StringMatch.OneofMatchType
import istio.proxy.v1.config._

class InternalTrafficIdentifierTest extends FunSuite {
  private val schemeThatTriggersRules = "https"
  private val hostThatTriggersRules = "svc-trigger-rules"

  val routeCache = new RouteCache {
    val rules = Map[String, RouteRule](
      "known-rule" -> RouteRule(
        destination = Some(s"${hostThatTriggersRules}-dst"),
        precedence = Some(2),
        `match` = Some(MatchCondition(httpHeaders = Map(
          "scheme" -> StringMatch(Some(OneofMatchType.Exact(schemeThatTriggersRules)))
        ))),
        rewrite = Some(HTTPRewrite(Some("/rewritten/to"), Some("rewritten-to-svc")))
      )
    )

    def getRules = Future.value(rules)

    def close(deadline: Time) = ???

    val routeRules = Activity.value(rules)
  }

  val clusters = new ClusterCache {
    val knownClusters = Map[String, Cluster](
      "known-svc" -> Cluster("known-svc-dst", "known-svc-port"),
      hostThatTriggersRules -> Cluster(s"${hostThatTriggersRules}-dst", "known-svc-port")
    )

    override def get(domain: String) = Future.value(knownClusters.get(domain))

    override def close(deadline: Time) = ???
  }

  val noOpMixerClient = new MixerClient(null) {
    override def report(
      responseCode: ResponseCodeIstioAttribute,
      requestPath: RequestPathIstioAttribute,
      targetService: TargetServiceIstioAttribute,
      sourceLabel: SourceLabelIstioAttribute,
      targetLabel: TargetLabelsIstioAttribute,
      duration: ResponseDurationIstioAttribute
    ) = Future.Done

    override def checkPreconditions(istioRequest: IstioRequest[_]) = Future.value(MixerCheckStatus(GrpcStatus.Ok("")))
  }

  class StubRequest(var host: String = "unset")

  val stubProtocolHandler = new IstioProtocolSpecificRequestHandler[StubRequest] {
    def redirectRequest(redir: HTTPRedirect, req: StubRequest): Future[Nothing] = ???

    def rewriteRequest(uri: String, authority: Option[String], req: StubRequest): Unit = req.host = authority.getOrElse("missing")
  }

  val internalTrafficIdentifier = new InternalTrafficIdentifier[StubRequest](
    Path.read("/my/service"),
    () => Dtab.empty,
    routeCache,
    clusters,
    noOpMixerClient,
    stubProtocolHandler
  )

  test("routes unknown :authority as external requests") {
    val istioRequestForUnknownAuthorityOnDefaultPort = IstioRequest(
      "/users/3",
      "http",
      "GET",
      "external-server-svc",
      (_) => None,
      new StubRequest(),
      None
    )

    val istioRequestForUnknownAuthorityOnSpecificPort =
      istioRequestForUnknownAuthorityOnDefaultPort.copy(authority = "other-external-svc:666")

    val identificationDefaultPort = await(internalTrafficIdentifier.identify(istioRequestForUnknownAuthorityOnDefaultPort)) match {
      case iR: IdentifiedRequest[StubRequest] => iR
      case other => fail(s"Unexpected identificationDefaultPort: ${other}")
    }
    assert(identificationDefaultPort.dst.path == Path.read("/my/service/ext/external-server-svc/80"))

    val identificationSpecificPort = await(internalTrafficIdentifier.identify(istioRequestForUnknownAuthorityOnSpecificPort)) match {
      case iR: IdentifiedRequest[StubRequest] => iR
      case other => fail(s"Unexpected identificationDefaultPort: ${other}")
    }
    assert(identificationSpecificPort.dst.path == Path.read("/my/service/ext/other-external-svc/666"))
  }

  test("forwards requests which have no matching rules to an empty label selector") {
    val istioRequestForUnknownAuthorityOnDefaultPort = IstioRequest(
      "/users/34",
      "http",
      "GET",
      "known-svc",
      (_) => None,
      new StubRequest(),
      None
    )

    val identificationDefaultPort = await(internalTrafficIdentifier.identify(istioRequestForUnknownAuthorityOnDefaultPort)) match {
      case iR: IdentifiedRequest[StubRequest] => iR
      case other => fail(s"Unexpected identificationDefaultPort: ${other}")
    }
    assert(identificationDefaultPort.dst.path == Path.read("/my/service/dest/known-svc-dst/::/known-svc-port"))
  }

  test("rewrites requests with matching rule") {
    val requestToBeRewritten = new StubRequest()
    val istioRequestWithMatchingRules = IstioRequest(
      "/users/34",
      schemeThatTriggersRules,
      "GET",
      hostThatTriggersRules,
      (_) => None,
      requestToBeRewritten,
      None
    )

    val identificationDefaultPort = await(internalTrafficIdentifier.identify(istioRequestWithMatchingRules)) match {
      case iR: IdentifiedRequest[StubRequest] => iR
      case other => fail(s"Unexpected identificationDefaultPort: ${other}")
    }
    assert(requestToBeRewritten.host == "rewritten-to-svc")
    assert(identificationDefaultPort.dst.path == Path.read("/my/service/route/known-rule/known-svc-port"))
  }
}
