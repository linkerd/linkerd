package io.buoyant.linkerd.protocol.h2.grpc

import com.twitter.finagle.buoyant.h2.service.{H2Classifier, H2ReqRep, H2ReqRepFrame}
import com.twitter.finagle.service.ResponseClass
import com.twitter.util.{Return, Throw}
import io.buoyant.grpc.runtime.GrpcStatus
import io.buoyant.grpc.runtime.GrpcStatus.{Ok, Unavailable}

trait GrpcClassifier extends H2Classifier {

  def retryable(status: GrpcStatus): Boolean

  /**
   * Since GRPC sends status codes in the
   * Trailers frame of an H2 stream, we can never attempt early classification, unless an error was
   * Thrown.
   */
  override val responseClassifier: PartialFunction[H2ReqRep, ResponseClass] = {
    case H2ReqRep(_, Throw(_)) => ResponseClass.NonRetryableFailure
    case H2ReqRep(_, Return(GrpcStatus(Ok(_)))) => ResponseClass.Success
    case H2ReqRep(_, Return(GrpcStatus(status))) =>
      if (retryable(status)) ResponseClass.RetryableFailure
      else ResponseClass.NonRetryableFailure
  }

  override def streamClassifier: PartialFunction[H2ReqRepFrame, ResponseClass] = {
    case H2ReqRepFrame(_, Return((_, Some(Return(GrpcStatus(Ok(_))))))) => ResponseClass.Success
    case H2ReqRepFrame(_, Return((_, Some(Return(GrpcStatus(status)))))) =>
      if (retryable(status)) ResponseClass.RetryableFailure
      else ResponseClass.NonRetryableFailure
    case _ => ResponseClass.NonRetryableFailure
  }
}

/**
 * H2Classifiers for gRPC
 */
object GrpcClassifiers {

  /**
   * H2Classifier that classifies all error status
   * codes as ResponseClass.RetryableFailure
   */
  object AlwaysRetryable extends GrpcClassifier {
    override def retryable(status: GrpcStatus): Boolean = true
  }

  /**
   * H2Classifier that classifies all error status
   * codes as ResponseClass.NonRetryableFailure
   */
  object NeverRetryable extends GrpcClassifier {
    override def retryable(status: GrpcStatus): Boolean = false
  }

  /**
   * The default H2Classifier for gRPC.
   *
   * Unavailable is marked as retryable, and all other
   * failures are marked as non-retryable.
   */
  object Default extends GrpcClassifier {
    override def retryable(status: GrpcStatus): Boolean = status match {
      case Unavailable(_) => true
      case _ => false
    }
  }

  /**
   * a GrpcClassifier that marks any error in a specified list of codes
   * as ResponseClass.RetryableFailure, and the rest as ResponseClass.NonRetryableFailure
   * @param retryableCodes a set of status codes which should be marked retryable
   */
  class RetryableStatusCodes(val retryableCodes: Set[Int]) extends GrpcClassifier {
    override def retryable(status: GrpcStatus): Boolean = retryableCodes(status.code)
  }
}
