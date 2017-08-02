package io.buoyant.linkerd.protocol.h2

import com.twitter.finagle.buoyant.h2.service.H2ReqRepFrame
import com.twitter.finagle.buoyant.h2.{Headers, Request, Response, Stream => FStream}
import com.twitter.finagle.service.ResponseClass
import com.twitter.util.Return
import io.buoyant.linkerd.protocol.h2.GrpcClassifier.{AlwaysRetryable, NeverRetryable}
import io.buoyant.grpc.runtime.GrpcStatus
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
}
