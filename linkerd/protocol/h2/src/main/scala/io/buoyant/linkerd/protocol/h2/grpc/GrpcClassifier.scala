package io.buoyant.linkerd.protocol.h2.grpc

import com.twitter.finagle.buoyant.h2.Status.{ClientError, ServerError}
import com.twitter.finagle.buoyant.h2.{Reset, Response, Status}
import com.twitter.finagle.buoyant.h2.service.{H2Classifier, H2ReqRep, H2ReqRepFrame}
import com.twitter.finagle.service.ResponseClass
import com.twitter.util.{Return, Throw}
import io.buoyant.grpc.runtime.GrpcStatus
import io.buoyant.grpc.runtime.GrpcStatus.{Ok, Unavailable}

trait GrpcClassifier extends H2Classifier {

  val nonAccruableCodes: Set[Int] = Set.empty

  def retryable(status: GrpcStatus): Boolean
  def retryable(status: Status): Boolean = false
  def retryable(throwable: Throwable): Boolean = false

  /**
   * Since GRPC sends status codes in the
   * Trailers frame of an H2 stream, we can never attempt early classification, unless an error was
   * Thrown.
   */
  override val responseClassifier: PartialFunction[H2ReqRep, ResponseClass] = {
    // Classify exceptions
    case H2ReqRep(_, Throw(throwable)) =>
      if (retryable(throwable)) ResponseClass.RetryableFailure
      else ResponseClass.NonRetryableFailure

    // Classify gRPC responses
    case H2ReqRep(_, Return(GrpcStatus(Ok(_)))) => ResponseClass.Success
    case H2ReqRep(_, Return(GrpcStatus(status))) =>
      if (nonAccruableCodes(status.code)) ResponseClass.Success
      else if (retryable(status)) ResponseClass.RetryableFailure
      else ResponseClass.NonRetryableFailure

    // Classify HTTP/2 responses
    case H2ReqRep(_, Return(Response(ClientError(status)))) =>
      if (retryable(status)) ResponseClass.RetryableFailure
      else ResponseClass.NonRetryableFailure
    case H2ReqRep(_, Return(Response(ServerError(status)))) =>
      if (retryable(status)) ResponseClass.RetryableFailure
      else ResponseClass.NonRetryableFailure
  }

  override def streamClassifier: PartialFunction[H2ReqRepFrame, ResponseClass] = {
    // Classify exceptions
    case H2ReqRepFrame(_, Return((_, Some(Throw(throwable))))) =>
      if (retryable(throwable)) ResponseClass.RetryableFailure
      else ResponseClass.NonRetryableFailure

    // Classify gRPC responses
    case H2ReqRepFrame(_, Return((_, Some(Return(GrpcStatus(Ok(_))))))) => ResponseClass.Success
    case H2ReqRepFrame(_, Return((_, Some(Return(GrpcStatus(status)))))) =>
      if (nonAccruableCodes(status.code)) ResponseClass.Success
      else if (retryable(status)) ResponseClass.RetryableFailure
      else ResponseClass.NonRetryableFailure

    // Classify HTTP/2 responses
    case H2ReqRepFrame(_, Return((Response(ClientError(status)), _))) =>
      if (retryable(status)) ResponseClass.RetryableFailure
      else ResponseClass.NonRetryableFailure
    case H2ReqRepFrame(_, Return((Response(ServerError(status)), _))) =>
      if (retryable(status)) ResponseClass.RetryableFailure
      else ResponseClass.NonRetryableFailure

    // Otherwise
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
   *
   * @param nonAccruableCodes a set of status codes which should be marked as success
   */
  class AlwaysRetryable(override val nonAccruableCodes: Set[Int] = Set.empty) extends GrpcClassifier {
    override def retryable(status: GrpcStatus): Boolean = true
  }

  /**
   * H2Classifier that classifies all error status
   * codes as ResponseClass.NonRetryableFailure
   *
   * @param nonAccruableCodes a set of status codes which should be marked as success
   */
  class NeverRetryable(override val nonAccruableCodes: Set[Int] = Set.empty) extends GrpcClassifier {
    override def retryable(status: GrpcStatus): Boolean = false
  }

  /**
   * The default H2Classifier for gRPC.
   *
   * Unavailable is marked as retryable, and all other
   * failures are marked as non-retryable.
   *
   * @param nonAccruableCodes a set of status codes which should be marked as success
   */
  class Default(override val nonAccruableCodes: Set[Int] = Set.empty) extends GrpcClassifier {
    override def retryable(status: GrpcStatus): Boolean = status match {
      case Unavailable(_) => true
      case _ => false
    }
  }

  /**
   * The fully standards compliant H2Classifier for gRPC.
   *
   * - gRPC UNAVILABLE response is marked as retryable.
   * - HTTP/2 RST_STREAM:REFUSED_STREAM is marked as retryable.
   * - HTTP/2 429, 502, 503, and 504 responses are marked as retryable.
   * - All other failures are marked as non-retryable.
   *
   * See: https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md#errors
   * See: https://github.com/grpc/grpc/blob/master/doc/http-grpc-status-mapping.md
   *
   * @param nonAccruableCodes a set of status codes which should be marked as success
   */
  class Compliant(override val nonAccruableCodes: Set[Int] = Set.empty) extends GrpcClassifier {
    override def retryable(status: GrpcStatus): Boolean = status match {
      case Unavailable(_) => true
      case _ => false
    }

    override def retryable(status: Status): Boolean =
      status == Status.TooManyRequests ||
        status == Status.ServiceUnavailable ||
        status == Status.BadGateway ||
        status == Status.GatewayTimeout

    override def retryable(throwable: Throwable): Boolean = throwable match {
      case Reset.Refused => true
      case _ => false
    }
  }

  /**
   * a GrpcClassifier that marks any error in a specified list of codes
   * as ResponseClass.RetryableFailure, and the rest as ResponseClass.NonRetryableFailure
   *
   * @param retryableCodes a set of status codes which should be marked retryable
   * @param nonAccruableCodes a set of status codes which should be marked as success
   */
  class RetryableStatusCodes(val retryableCodes: Set[Int], override val nonAccruableCodes: Set[Int] = Set.empty) extends GrpcClassifier {
    override def retryable(status: GrpcStatus): Boolean = retryableCodes(status.code)
  }
}
