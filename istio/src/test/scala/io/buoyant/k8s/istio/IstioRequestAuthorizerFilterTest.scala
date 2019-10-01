package io.buoyant.linkerd.protocol.h2.istio

import com.twitter.finagle.Service
import com.twitter.util.{Duration, Future, Try}
import io.buoyant.grpc.runtime.GrpcStatus
import io.buoyant.k8s.istio._
import io.buoyant.k8s.istio.mixer.{MixerCheckStatus, MixerClient}
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class IstioRequestAuthorizerFilterTest extends FunSuite with Awaits {

  case class Req()

  case class Resp(failed: Boolean = false)

  class TestIstioRequestAuthorizerFilter(mixerClient: MixerClient) extends IstioRequestAuthorizerFilter[Req, Resp](mixerClient) {
    def toIstioRequest(req: Req): IstioRequest[Req] = IstioRequest("uri", "scheme", "method", "authority", (_) => None, req, None)

    def toIstioResponse(resp: Try[Resp], duration: Duration): IstioResponse[Resp] = IstioResponse(-1, Duration.Bottom, Some(resp.get()))

    def toFailedResponse(code: Int, reason: String): Resp = Resp(true)
  }

  val noOpSvc = Service.mk[Req, Resp] { req =>
    Future.value(Resp())
  }

  class NoOpMixerClient extends MixerClient(null) {
    var reports = 0

    override def checkPreconditions(istioRequest: IstioRequest[_]): Future[MixerCheckStatus] = Future.value(MixerCheckStatus(GrpcStatus.Ok()))

    override def report(
      responseCode: ResponseCodeIstioAttribute,
      requestPath: RequestPathIstioAttribute,
      targetService: TargetServiceIstioAttribute,
      sourceLabel: SourceLabelIstioAttribute,
      targetLabel: TargetLabelsIstioAttribute,
      duration: ResponseDurationIstioAttribute
    ): Future[Unit] = {
      reports += 1
      Future.Done
    }
  }

  test("reports request and result when pre-conditions pass") {
    val mixerClient = new NoOpMixerClient()
    val istioRequestAuthorizer = new TestIstioRequestAuthorizerFilter(mixerClient)

    assert(mixerClient.reports == 0)
    istioRequestAuthorizer(Req(), noOpSvc)
    assert(mixerClient.reports == 1)
  }

  test("doest report request and result when pre-conditions fail") {
    val mixerClient = new NoOpMixerClient {
      override def checkPreconditions(istioRequest: IstioRequest[_]) = Future.value(MixerCheckStatus(GrpcStatus.PermissionDenied()))
    }
    val istioRequestAuthorizer = new TestIstioRequestAuthorizerFilter(mixerClient)

    assert(mixerClient.reports == 0)
    istioRequestAuthorizer(Req(), noOpSvc)
    assert(mixerClient.reports == 0)
  }

  test("calls service if pre-condition check succeeds") {
    var callCount = 0

    val mixerClient = new NoOpMixerClient {
      override def checkPreconditions(istioRequest: IstioRequest[_]) = Future.value(MixerCheckStatus(GrpcStatus.Ok()))
    }
    val istioRequestAuthorizer = new TestIstioRequestAuthorizerFilter(mixerClient)

    val noOpSvc = Service.mk[Req, Resp] { req =>
      callCount = callCount + 1
      Future.value(Resp())
    }

    assert(callCount == 0)
    assert(await(istioRequestAuthorizer(Req(), noOpSvc)) == Resp(false))
    assert(callCount == 1)
  }

  test("doesn't call service if pre-condition check fails") {
    var callCount = 0

    val mixerClient = new NoOpMixerClient {
      override def checkPreconditions(istioRequest: IstioRequest[_]) = Future.value(MixerCheckStatus(GrpcStatus.PermissionDenied()))
    }
    val istioRequestAuthorizer = new TestIstioRequestAuthorizerFilter(mixerClient)

    val noOpSvc = Service.mk[Req, Resp] { req =>
      callCount = callCount + 1
      Future.value(Resp())
    }

    assert(callCount == 0)
    assert(await(istioRequestAuthorizer(Req(), noOpSvc)) == Resp(true))
    assert(callCount == 0)
  }
}