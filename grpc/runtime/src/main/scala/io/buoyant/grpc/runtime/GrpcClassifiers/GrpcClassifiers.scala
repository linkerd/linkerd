package io.buoyant.grpc.runtime

import com.twitter.finagle.buoyant.h2.service.{H2Classifier, H2ReqRep, H2ReqRepFrame}
import com.twitter.finagle.service.ResponseClass
import com.twitter.util.Return
import io.buoyant.grpc.runtime.GrpcStatus.{Internal, Ok, Unavailable}

/**
  * [[H2Classifier]]s for gRPC
  */
object GrpcClassifiers {

  private[this] val AlwaysRetryable: PartialFunction[GrpcStatus, Boolean] = {
    case Unavailable(_) => true
  }

  private[this] val RetryableIfIdempotent: PartialFunction[GrpcStatus, Boolean] =
    AlwaysRetryable.orElse {
      case Internal(_) => true
    }


  /**
    * [[H2Classifier]] for idempotent gRPC requests
    */
  object IdempotentRequest extends H2Classifier {

    override val streamClassifier: PartialFunction[H2ReqRepFrame, ResponseClass] = {
      case H2ReqRepFrame(_, Return((_, Some(Return(GrpcStatus(Ok(_))))))) =>
        ResponseClass.Success
      case H2ReqRepFrame(_, Return((_, Some(Return(GrpcStatus(status))))))
        if RetryableIfIdempotent(status) => ResponseClass.RetryableFailure
      case _ => ResponseClass.RetryableFailure
    }

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
    * [[H2Classifier]] for non-idempotent gRPC requests
    */
  object NonIdempotentRequest extends H2Classifier {
    override val streamClassifier: PartialFunction[H2ReqRepFrame, ResponseClass] = {
      case H2ReqRepFrame(_, Return((_, Some(Return(GrpcStatus(Ok(_))))))) =>
        ResponseClass.Success
      case H2ReqRepFrame(_, Return((_, Some(Return(GrpcStatus(status))))))
        if AlwaysRetryable(status) => ResponseClass.RetryableFailure
      case _ => ResponseClass.RetryableFailure
    }


    /**
      * @inheritdoc
      * Since GRPC sends status codes in the
      * [[com.twitter.finagle.buoyant.h2.Frame.Trailers Trailers]] frame of an H2
      * stream, we can never attempt early classification
      */
    override val responseClassifier: PartialFunction[H2ReqRep, ResponseClass] =
      PartialFunction.empty
  }
}
