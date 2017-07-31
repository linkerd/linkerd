package io.buoyant.grpc.runtime

import com.twitter.finagle.buoyant.h2.service.{H2Classifier, H2ReqRep, H2ReqRepFrame}
import com.twitter.finagle.service.ResponseClass
import com.twitter.util.Return
import io.buoyant.grpc.runtime.GrpcStatus.{Internal, Ok, Unavailable}


trait GrpcClassifier extends H2Classifier {
  /**
    * @inheritdoc
    * Since GRPC sends status codes in the
    * [[com.twitter.finagle.buoyant.h2.Frame.Trailers Trailers]] frame of an H2
    * stream, we can never attempt early classification
    */
  override val responseClassifier: PartialFunction[H2ReqRep, ResponseClass] =
    PartialFunction.empty
}


/**
  * [[H2Classifier]]s for gRPC
  */
object GrpcClassifier {

  /**
    * [[H2Classifier]] that classifies all error status
    * codes as [[ResponseClass.RetryableFailure]]
    */
  object AlwaysRetryable extends GrpcClassifier {

    override val streamClassifier: PartialFunction[H2ReqRepFrame, ResponseClass] = {
      case H2ReqRepFrame(_, Return((_, Some(Return(GrpcStatus(Ok(_))))))) =>
        ResponseClass.Success
      case H2ReqRepFrame(_, Return((_, Some(Return(GrpcStatus(_)))))) =>
        ResponseClass.RetryableFailure
    }

  }

  /**
    * [[H2Classifier]] that classifies all error status
    * codes as [[ResponseClass.NonRetryableFailure]]
    */
  object NeverRetryable extends GrpcClassifier {

    override val streamClassifier: PartialFunction[H2ReqRepFrame, ResponseClass] = {
      case H2ReqRepFrame(_, Return((_, Some(Return(GrpcStatus(Ok(_))))))) =>
        ResponseClass.Success
      case H2ReqRepFrame(_, Return((_, Some(Return(GrpcStatus(_)))))) =>
        ResponseClass.NonRetryableFailure
    }

  }


}
