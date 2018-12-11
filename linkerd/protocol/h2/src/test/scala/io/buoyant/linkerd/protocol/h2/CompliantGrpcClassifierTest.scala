package io.buoyant.linkerd.protocol.h2

import com.twitter.finagle.buoyant.h2.Frame.Trailers
import com.twitter.finagle.buoyant.h2.service.{H2ReqRep, H2ReqRepFrame}
import com.twitter.finagle.buoyant.h2.{Headers, Request, Reset, Response, Status, Stream => FStream}
import com.twitter.finagle.service.ResponseClass
import com.twitter.util.{Return, Throw}
import io.buoyant.grpc.runtime.GrpcStatus
import io.buoyant.linkerd.protocol.h2.grpc.GrpcClassifiers.Compliant
import org.scalacheck.Arbitrary
import org.scalatest.FunSuite
import org.scalatest.prop.GeneratorDrivenPropertyChecks

class CompliantGrpcClassifierTest extends FunSuite with GeneratorDrivenPropertyChecks {
  implicit val arbitraryStatus: Arbitrary[GrpcStatus] = Arbitrary(for {
    code <- Arbitrary.arbitrary[Int]
    msg <- Arbitrary.arbitrary[String]
  } yield { GrpcStatus(code, msg) })

  // Single tests
  test("Compliant classifies UNAVAILABLE as retryable") {
    forAll("status") { status: GrpcStatus =>
      val reqrep = H2ReqRep(
        Request(Headers.empty, FStream.empty()),
        Return(Response(status.toTrailers, FStream.empty()))
      )
      val compliant = new Compliant
      assert(compliant.responseClassifier.isDefinedAt(reqrep))
      if (status.code != 0) {
        assert(compliant.responseClassifier(reqrep) == ResponseClass.NonRetryableFailure)
      } else if (status.code == 14) {
        assert(compliant.responseClassifier(reqrep) == ResponseClass.RetryableFailure)
      } else {
        assert(compliant.responseClassifier(reqrep) == ResponseClass.Success)
      }
    }
  }

  test("Compliant classifies Reset:Refused as retryable") {
    val reqrep = H2ReqRep(
      Request(Headers.empty, FStream.empty()),
      Throw(Reset.Refused)
    )
    val compliant = new Compliant
    assert(compliant.responseClassifier.isDefinedAt(reqrep))
    assert(compliant.responseClassifier(reqrep) == ResponseClass.RetryableFailure)
  }

  test("Compliant classifies Reset:Other as non-retryable") {
    val reqrep = H2ReqRep(
      Request(Headers.empty, FStream.empty()),
      Throw(Reset.EnhanceYourCalm)
    )
    val compliant = new Compliant
    assert(compliant.responseClassifier.isDefinedAt(reqrep))
    assert(compliant.responseClassifier(reqrep) == ResponseClass.NonRetryableFailure)
  }

  test("Compliant classifies retryable http responses") {
    List(Status.ServiceUnavailable, Status.BadGateway, Status.GatewayTimeout, Status.TooManyRequests
    ).foreach(status => {
      val reqrep = H2ReqRep(
        Request(Headers.empty, FStream.empty()),
        Return(Response(status, FStream.empty()))
      )
      val compliant = new Compliant
      assert(compliant.responseClassifier.isDefinedAt(reqrep))
      assert(compliant.responseClassifier(reqrep) == ResponseClass.RetryableFailure)
    })
  }

  test("Compliant classifies non-retryable http responses") {
    List(Status.Forbidden, Status.Unauthorized, Status.InternalServerError).foreach(status => {
      val reqrep = H2ReqRep(
        Request(Headers.empty, FStream.empty()),
        Return(Response(status, FStream.empty()))
      )
      val compliant = new Compliant
      assert(compliant.responseClassifier.isDefinedAt(reqrep))
      assert(compliant.responseClassifier(reqrep) == ResponseClass.NonRetryableFailure)
    })
  }

  // Streaming tests
  test("Compliant classifies UNAVAILABLE stream as retryable") {
    forAll("status") { status: GrpcStatus =>
      val trailers = status.toTrailers
      val reqrep = H2ReqRepFrame(
        Request(Headers.empty, FStream.empty()),
        Return((
          Response(Headers.empty, FStream.empty()),
          Some(Return(trailers))
        ))
      )
      val compliant = new Compliant
      assert(compliant.streamClassifier.isDefinedAt(reqrep))
      if (status.code != 0) {
        assert(compliant.streamClassifier(reqrep) == ResponseClass.NonRetryableFailure)
      } else if (status.code == 14) {
        assert(compliant.streamClassifier(reqrep) == ResponseClass.RetryableFailure)
      } else {
        assert(compliant.streamClassifier(reqrep) == ResponseClass.Success)
      }
    }
  }

  test("Compliant classifies Reset:Refused stream as retryable") {
    val reqrep = H2ReqRepFrame(
      Request(Headers.empty, FStream.empty()),
      Return((
        Response(Status.Ok, FStream.empty()),
        Some(Throw(Reset.Refused))
      ))
    )
    val compliant = new Compliant
    assert(compliant.streamClassifier.isDefinedAt(reqrep))
    assert(compliant.streamClassifier(reqrep) == ResponseClass.RetryableFailure)
  }

  test("Compliant classifies Reset:Other stream as non-retryable") {
    val reqrep = H2ReqRepFrame(
      Request(Headers.empty, FStream.empty()),
      Return((
        Response(Status.Ok, FStream.empty()),
        Some(Throw(Reset.EnhanceYourCalm))
      ))
    )
    val compliant = new Compliant
    assert(compliant.streamClassifier.isDefinedAt(reqrep))
    assert(compliant.streamClassifier(reqrep) == ResponseClass.NonRetryableFailure)
  }

  test("Compliant classifies retryable stream http responses") {
    List(Status.ServiceUnavailable, Status.BadGateway, Status.GatewayTimeout, Status.TooManyRequests
    ).foreach(status => {
      val reqrep = H2ReqRepFrame(
        Request(Headers.empty, FStream.empty()),
        Return((
          Response(status, FStream.empty()),
          Some(Return(Trailers()))
        ))
      )
      val compliant = new Compliant
      assert(compliant.streamClassifier.isDefinedAt(reqrep))
      assert(compliant.streamClassifier(reqrep) == ResponseClass.RetryableFailure)
    })
  }

  test("Compliant classifies non-retryable stream http responses") {
    List(Status.Forbidden, Status.Unauthorized, Status.InternalServerError).foreach(status => {
      val reqrep = H2ReqRepFrame(
        Request(Headers.empty, FStream.empty()),
        Return((
          Response(status, FStream.empty()),
          Some(Return(Trailers()))
        ))
      )
      val compliant = new Compliant
      assert(compliant.streamClassifier.isDefinedAt(reqrep))
      assert(compliant.streamClassifier(reqrep) == ResponseClass.NonRetryableFailure)
    })
  }
}