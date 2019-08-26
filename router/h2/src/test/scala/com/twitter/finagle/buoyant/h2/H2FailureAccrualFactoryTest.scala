package com.twitter.finagle.buoyant.h2

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.buoyant.h2.service.{H2Classifier, H2ReqRep, H2ReqRepFrame}
import com.twitter.finagle.context.Contexts
import com.twitter.finagle.liveness.FailureAccrualPolicy
import com.twitter.finagle.service.ResponseClass
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.finagle.{FactoryToService, Service, ServiceFactory, Status => FStatus}
import com.twitter.util.{Duration, Future, MockTimer, Return}
import io.buoyant.router.DiscardingFactoryToService
import io.buoyant.router.DiscardingFactoryToService.RequestDiscarder
import io.buoyant.router.context.h2.H2ClassifierCtx
import io.buoyant.test.FunSuite
import java.util
import scala.{Stream => SStream}

class H2FailureAccrualFactoryTest extends FunSuite {

  val timer = new MockTimer()

  test("response failure accrual") {

    val classifier = new H2Classifier {
      override def responseClassifier: PartialFunction[H2ReqRep, ResponseClass] = {
        case H2ReqRep(_, Return(rep)) =>
          if (rep.headers.get(":status") == Some("200")) ResponseClass.Success
          else ResponseClass.NonRetryableFailure
        case _ =>
          ResponseClass.NonRetryableFailure
      }
    }

    val stats = new InMemoryStatsReceiver

    val underlying = ServiceFactory.const(Service.mk { req: Request =>
      Future.value(Response(Status.InternalServerError, Stream.empty()))
    })

    val fa = new H2FailureAccrualFactory(
      underlying,
      FailureAccrualPolicy.consecutiveFailures(5, SStream.continually(Duration.Top)),
      timer,
      stats
    )

    val svc = new DiscardingFactoryToService(RequestDiscarder[Request](_ => ()), fa)

    Contexts.local.let(H2ClassifierCtx, param.H2Classifier(classifier)) {
      for (_ <- 1 to 4) {
        await(svc(Request(Headers(":method" -> "GET"), Stream.empty())))
        assert(fa.status == FStatus.Open)
      }
      await(svc(Request(Headers(":method" -> "GET"), Stream.empty())))
      assert(fa.status == FStatus.Busy)
    }
  }

  test("stream failure accrual") {

    val classifier = new H2Classifier {
      override def streamClassifier: PartialFunction[H2ReqRepFrame, ResponseClass] = {
        case H2ReqRepFrame(_, Return((_, Some(Return(f: Frame.Trailers))))) =>
          if (f.get("grpc-status") == Some("0")) ResponseClass.Success
          else ResponseClass.NonRetryableFailure
        case _ =>
          ResponseClass.NonRetryableFailure
      }
    }

    val stats = new InMemoryStatsReceiver

    val serverLocalQs = new util.ArrayList[AsyncQueue[Frame]]()

    val underlying = ServiceFactory.const(
      Service.mk { req: Request =>
        val q = new AsyncQueue[Frame]()
        serverLocalQs.add(q)
        Future.value(Response(Status.Ok, Stream(q)))
      }
    )

    val fa = new H2FailureAccrualFactory(
      underlying,
      FailureAccrualPolicy.consecutiveFailures(5, SStream.continually(Duration.Top)),
      timer,
      stats
    )

    val svc = new FactoryToService(fa)

    Contexts.local.let(H2ClassifierCtx, param.H2Classifier(classifier)) {
      val rsps = for (_ <- 1 to 5) yield {
        await(svc(Request(Headers(":method" -> "GET"), Stream.empty())))
      }

      assert(fa.status == FStatus.Open)

      for (i <- 0 until 4) {
        serverLocalQs.get(i).offer(Frame.Trailers("grpc-status" -> "1"))
        await(rsps(i).stream.read())
        assert(fa.status == FStatus.Open)
      }
      serverLocalQs.get(4).offer(Frame.Trailers("grpc-status" -> "1"))
      await(rsps(4).stream.read())
      assert(fa.status == FStatus.Busy)
    }
  }
}
