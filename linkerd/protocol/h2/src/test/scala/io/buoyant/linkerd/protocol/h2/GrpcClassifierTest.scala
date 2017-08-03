package io.buoyant.linkerd.protocol.h2.grpc

import com.twitter.finagle.buoyant.h2.service.H2ReqRepFrame
import com.twitter.finagle.buoyant.h2.{Headers, Request, Response, Stream => FStream}
import com.twitter.finagle.service.ResponseClass
import com.twitter.finagle.util.LoadService
import com.twitter.util.Return
import io.buoyant.config.Parser
import io.buoyant.linkerd.protocol.h2.grpc.GrpcClassifiers.{AlwaysRetryable, NeverRetryable, RetryableStatusCodes}
import io.buoyant.grpc.runtime.GrpcStatus
import io.buoyant.linkerd.RouterConfig
import io.buoyant.linkerd.protocol.h2.H2ClassifierInitializer
import io.buoyant.linkerd.protocol.{H2DefaultSvc, H2Initializer}
import org.scalacheck.Arbitrary
import org.scalatest.FunSuite
import org.scalatest.prop.GeneratorDrivenPropertyChecks

class GrpcClassifierTest extends FunSuite with GeneratorDrivenPropertyChecks {
  implicit val arbitraryStatus: Arbitrary[GrpcStatus] = Arbitrary(for {
    code <- Arbitrary.arbitrary[Int]
    msg <- Arbitrary.arbitrary[String]
  } yield { GrpcStatus(code, msg) })

  test("AlwaysRetryable classifies all errors as retryable") {
    forAll("status") { status: GrpcStatus =>
      val trailers = status.toTrailers
      val reqrep = H2ReqRepFrame(
        Request(Headers.empty, FStream.empty()),
        Return((
          Response(Headers.empty, FStream.empty()),
          Some(Return(trailers))
        ))
      )
      assert(AlwaysRetryable.streamClassifier.isDefinedAt(reqrep))
      if (status.code != 0) {
        assert(AlwaysRetryable.streamClassifier(reqrep) == ResponseClass.RetryableFailure)
      } else {
        assert(AlwaysRetryable.streamClassifier(reqrep) == ResponseClass.Success)
      }
    }
  }

  test("NeverRetryable classifies no errors as retryable") {
    forAll("status") { status: GrpcStatus =>
      val trailers = status.toTrailers
      val reqrep = H2ReqRepFrame(
        Request(Headers.empty, FStream.empty()),
        Return((
          Response(Headers.empty, FStream.empty()),
          Some(Return(trailers))
        ))
      )
      assert(NeverRetryable.streamClassifier.isDefinedAt(reqrep))
      if (status.code != 0) {
        assert(NeverRetryable.streamClassifier(reqrep) == ResponseClass.NonRetryableFailure)
      } else {
        assert(NeverRetryable.streamClassifier(reqrep) == ResponseClass.Success)
      }
    }
  }

  test("RetryableStatusCodes classifies specific codes as retryable") {
    forAll("status", "retryable statuses") { (status: GrpcStatus, codes: Set[Int]) =>
      val trailers = status.toTrailers
      val reqrep = H2ReqRepFrame(
        Request(Headers.empty, FStream.empty()),
        Return((
          Response(Headers.empty, FStream.empty()),
          Some(Return(trailers))
        ))
      )
      val classifier = new RetryableStatusCodes(codes)
      assert(classifier.streamClassifier.isDefinedAt(reqrep))
      if (status.code == 0) {
        assert(classifier.streamClassifier(reqrep) == ResponseClass.Success)
      } else if (codes.contains(status.code)) {
        assert(classifier.streamClassifier(reqrep) == ResponseClass.RetryableFailure)
      } else {
        assert(classifier.streamClassifier(reqrep) == ResponseClass.NonRetryableFailure)
      }
    }
  }

  for {
    init <- Seq(
      AlwaysRetryableInitializer,
      NeverRetryableInitializer,
      DefaultInitializer
    )
    kind = init.configId
  } {

    test(s"loads $kind") {
      assert(LoadService[H2ClassifierInitializer]().exists(_.configId == kind))
    }

    test(s"parse router with $kind") {
      val yaml =
        s"""|protocol: h2
            |service:
            |  responseClassifier:
            |    kind: $kind
            |servers:
            |- port: 0
            |""".stripMargin
      val mapper = Parser.objectMapper(yaml, Iterable(Seq(H2Initializer), Seq(init)))
      val router = mapper.readValue[RouterConfig](yaml)
      assert(router.service.get.asInstanceOf[H2DefaultSvc]._h2Classifier.isDefined)
      assertThrows[UnsupportedOperationException] {
        router.service.get.asInstanceOf[H2DefaultSvc].responseClassifierConfig
      }
    }
  }

  test("loads io.l5d.h2.grpc.retryableStatusCodes") {
    assert(LoadService[H2ClassifierInitializer]().exists(_.configId == "io.l5d.h2.grpc.retryableStatusCodes"))
  }

  test("parse router with io.l5d.h2.grpc.retryableStatusCodes") {
    val yaml =
      s"""|protocol: h2
          |service:
          |  responseClassifier:
          |    kind: io.l5d.h2.grpc.retryableStatusCodes
          |    retryableStatusCodes:
          |    - 1
          |    - 2
          |    - 3
          |servers:
          |- port: 0
          |""".stripMargin
    val mapper = Parser.objectMapper(yaml, Iterable(Seq(H2Initializer), Seq(RetryableStatusCodesInitializer)))
    val router = mapper.readValue[RouterConfig](yaml)
    assert(router.service.get.asInstanceOf[H2DefaultSvc]._h2Classifier.isDefined)
    assertThrows[UnsupportedOperationException] {
      router.service.get.asInstanceOf[H2DefaultSvc].responseClassifierConfig
    }
  }
}
