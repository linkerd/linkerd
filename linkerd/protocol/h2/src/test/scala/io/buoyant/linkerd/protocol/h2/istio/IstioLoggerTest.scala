package io.buoyant.linkerd.protocol.h2.istio

import com.twitter.finagle.buoyant.H2
import com.twitter.finagle.buoyant.h2.{Headers, Request, Response, Status, Stream => H2Stream}
import com.twitter.finagle.{Service, Stack}
import com.twitter.util.Future
import io.buoyant.grpc.runtime.Stream
import io.buoyant.k8s.istio._
import io.buoyant.k8s.istio.mixer.MixerClient
import io.buoyant.test.Awaits
import istio.mixer.v1.{Mixer, ReportResponse}
import org.scalatest.FunSuite

class IstioLoggerTest extends FunSuite with Awaits {

  class MockMixerClient extends MixerClient(new Mixer.Client(H2.client.newService("example.com:80"))) {
    var reports = 0

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

  val mixerClient = new MockMixerClient()

  test("creates a logger") {
    val logger = new IstioLogger(mixerClient, Stack.Params.empty)
    assert(mixerClient.reports == 0)
  }

  test("apply triggers a mixer report") {
    val logger = new IstioLogger(mixerClient, Stack.Params.empty)
    val svc = Service.mk[Request, Response] { req =>
      Future.value(Response(Status.Ok, H2Stream.const("")))
    }

    assert(mixerClient.reports == 0)
    logger(Request(Headers(Headers.Method -> "GET"), H2Stream.const("")), svc)
    assert(mixerClient.reports == 1)
  }
}

