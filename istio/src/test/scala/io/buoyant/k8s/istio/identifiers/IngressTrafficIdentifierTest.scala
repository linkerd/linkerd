package io.buoyant.k8s.istio.identifiers

import com.twitter.finagle.{Dtab, Path}
import com.twitter.util.{Activity, Future, Time}
import io.buoyant.grpc.runtime.GrpcStatus
import io.buoyant.k8s.IngressPath
import io.buoyant.k8s.istio._
import io.buoyant.k8s.istio.mixer.{MixerCheckStatus, MixerClient}
import io.buoyant.router.RoutingFactory.{IdentifiedRequest, UnidentifiedRequest}
import io.buoyant.test.FunSuite
import istio.proxy.v1.config.StringMatch.OneofMatchType
import istio.proxy.v1.config._

class IngressTrafficIdentifierTest extends FunSuite {
  private val schemeThatTriggersRules = "https"
  private val hostThatTriggersRules = "svc-trigger-rules"
  val portHostTriggerRules = "8089"
  private val knownHost = "known-svc"
  private val knownHostPort = "8087"

  val routeCache = new RouteCache {
    val rules = Map[String, RouteRule](
      "known-rule" -> RouteRule(
        destination = Some(s"${hostThatTriggersRules}.default.svc.cluster.local"),
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
      s"${knownHost}.default.svc.cluster.local:${knownHostPort}" -> Cluster(s"${knownHost}-dst", "known-svc-port"),
      s"${hostThatTriggersRules}.default.svc.cluster.local:${portHostTriggerRules}" -> Cluster(s"${hostThatTriggersRules}-dst", "known-svc-port")
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

  val ingressTrafficIdentifier = new IngresssTrafficIdentifier[StubRequest](
    Path.read("/my/service"),
    () => Dtab.empty,
    routeCache,
    clusters,
    noOpMixerClient,
    stubProtocolHandler
  )

  val ingressPathWithKnownVhost = IngressPath(
    host = Some("known-svc"),
    path = None,
    namespace = "default",
    svc = knownHost,
    port = knownHostPort
  )

  test("rejects request with no matching ingress rules") {
    val istioRequestWithNoMatchingIngressRules = IstioRequest(
      "/users/3",
      "http",
      "GET",
      "external-server-svc",
      (_) => None,
      new StubRequest(),
      None
    )

    await(ingressTrafficIdentifier.identify(istioRequestWithNoMatchingIngressRules, Future.value(None))) match {
      case iR: UnidentifiedRequest[StubRequest] => succeed
      case other => fail(s"Unexpected identification: ${other}")
    }
  }

  test("rejects request with unknown vhost") {
    val istioRequestForUnknownAuthorityOnDefaultPort = IstioRequest(
      "/users/3",
      "http",
      "GET",
      "unknown-server-svc",
      (_) => None,
      new StubRequest(),
      None
    )

    val ingressPathWithUnknownVhost = IngressPath(
      host = Some("vhost-nobody-knows"),
      path = None,
      namespace = "default",
      svc = "vhost-nobody-knows-svc",
      port = "9091"
    )

    await(ingressTrafficIdentifier.identify(istioRequestForUnknownAuthorityOnDefaultPort, Future.value(Some(ingressPathWithUnknownVhost)))) match {
      case other: IdentifiedRequest[StubRequest] => fail(s"Unexpected identification: ${other.dst}")
      case iR: UnidentifiedRequest[StubRequest] => succeed
    }
  }

  test("forwards requests which have no matching rules to an empty label selector") {
    val istioRequestWithNoMatchingRules = IstioRequest(
      "/users/34",
      "http",
      "GET",
      knownHost,
      (_) => None,
      new StubRequest(),
      None
    )

    val identificationDefaultPort = await(ingressTrafficIdentifier.identify(istioRequestWithNoMatchingRules, Future.value(Some(ingressPathWithKnownVhost)))) match {
      case iR: IdentifiedRequest[StubRequest] => iR
      case other: UnidentifiedRequest[StubRequest] => fail(s"Unexpected identificationDefaultPort: ${other.reason}")
    }
    assert(identificationDefaultPort.dst.path == Path.read("/my/service/dest/known-svc.default.svc.cluster.local/::/known-svc-port"))
  }

  test("rewrites requests with matching rule") {
    val requestToBeRewritten = new StubRequest()
    val istioRequestForKnownVhostWithMatchingRules = IstioRequest(
      "/users/34",
      schemeThatTriggersRules,
      "GET",
      hostThatTriggersRules,
      (_) => None,
      requestToBeRewritten,
      None
    )

    val ingressPathWithRules = IngressPath(
      host = Some(hostThatTriggersRules),
      path = None,
      namespace = "default",
      svc = hostThatTriggersRules,
      port = portHostTriggerRules
    )

    val identificationDefaultPort = await(ingressTrafficIdentifier.identify(istioRequestForKnownVhostWithMatchingRules, Future.value(Some(ingressPathWithRules)))) match {
      case iR: IdentifiedRequest[StubRequest] => iR
      case other => fail(s"Unexpected identificationDefaultPort: ${other}")
    }
    assert(requestToBeRewritten.host == "rewritten-to-svc")
    assert(identificationDefaultPort.dst.path == Path.read("/my/service/route/known-rule/known-svc-port"))
  }
}
