package io.buoyant.k8s.istio.mixer

import com.twitter.finagle.Service
import com.twitter.finagle.buoyant.h2
import com.twitter.util.{Duration, Future}
import io.buoyant.grpc.runtime.{GrpcStatus, Stream}
import io.buoyant.k8s.istio._
import io.buoyant.test.{Awaits, Exceptions}
import istio.mixer.v1._
import org.scalatest.FunSuite
import com.google.rpc.StatusProto
import com.twitter.finagle.http.Request

class MixerClientTest extends FunSuite with Awaits with Exceptions {

  test("report - makes a service call") {
    var calls = 0
    val service = new Mixer {
      override def report(req: Stream[ReportRequest]) = {
        calls += 1
        Stream.value(ReportResponse())
      }

      override def check(req: Stream[CheckRequest]) = ???

      override def quota(req: Stream[QuotaRequest]) = ???
    }

    val mixerClient = new MixerClient(service)
    assert(calls == 0)
    val rsp = mixerClient.report(
      ResponseCodeIstioAttribute(200),
      RequestPathIstioAttribute("requestPath"),
      TargetServiceIstioAttribute("targetService"),
      SourceLabelIstioAttribute(Map.empty),
      TargetLabelsIstioAttribute(Map.empty),
      ResponseDurationIstioAttribute(Duration.Zero)
    )
    assert(calls == 1)
  }

  test("check - allows if status was 'ok'") {
    val returnedCode = GrpcStatus.Ok("").code
    var calls = 0
    val service = new Mixer {
      override def report(req: Stream[ReportRequest]) = ???

      override def check(req: Stream[CheckRequest]) = {
        calls += 1
        val status = StatusProto.Status(code = Some(returnedCode))
        Stream.value(CheckResponse(result = Some(status)))
      }

      override def quota(req: Stream[QuotaRequest]) = ???
    }

    val mixerClient = new MixerClient(service)
    assert(calls == 0)

    val req = Request()
    val rsp = await(mixerClient.checkPreconditions(
      IstioRequest[Request](
        "/users/23",
        "http",
        "POST",
        "localhost",
        (_) => None,
        req,
        None
      )
    ))

    assert(calls == 1)
    assert(rsp.grpcStatus.code == returnedCode)
    assert(rsp.success)
  }

  test("check - returns denial if status wasnt 'ok'") {
    var calls = 0
    val service = new Mixer {
      override def report(req: Stream[ReportRequest]) = ???

      override def check(req: Stream[CheckRequest]) = {
        calls += 1
        val status = StatusProto.Status(code = Some(GrpcStatus.PermissionDenied("").code))
        Stream.value(CheckResponse(result = Some(status)))
      }

      override def quota(req: Stream[QuotaRequest]) = ???
    }

    val mixerClient = new MixerClient(service)
    assert(calls == 0)

    val rsp = await(mixerClient.checkPreconditions(
      IstioRequest(
        "/users/23",
        "http",
        "POST",
        "localhost",
        (_) => None,
        Request(),
        None
      )
    ))

    assert(calls == 1)
    assert(!rsp.success)
  }

  test("check - errors when invoking server means denial") {
    var calls = 0
    val service = new Mixer {
      override def report(req: Stream[ReportRequest]) = ???

      override def check(req: Stream[CheckRequest]) = {
        calls += 1
        Stream.exception(new UnsupportedOperationException("expected"))
      }

      override def quota(req: Stream[QuotaRequest]) = ???
    }

    val mixerClient = new MixerClient(service)
    assert(calls == 0)

    val rsp = await(mixerClient.checkPreconditions(
      IstioRequest(
        "/users/23",
        "http",
        "POST",
        "localhost",
        (_) => None,
        Request(),
        None
      )
    ))

    assert(calls == 1)
    assert(!rsp.success)
  }

}
