package io.buoyant.grpc

import scala.util.control.NoStackTrace
import com.twitter.finagle.buoyant.h2

sealed trait Error
  extends Throwable
  with NoStackTrace {
  def errorCode: Int
}

sealed trait GrpcError extends Error

object GrpcError {
  // These error codes came from grpc-go/codes/codes.go
  object NoError extends GrpcError {
    override def toString = "OK"
    override def errorCode = 0
  }

  object Cancelled extends GrpcError {
    override def toString = "CANCELLED"
    override def errorCode = 1
  }

  object Unknown extends GrpcError {
    override def toString = "UNKNOWN"
    override def errorCode = 2
  }

  object InvalidArgument extends GrpcError {
    override def toString = "INVALID_ARGUMENT"
    override def errorCode = 3
  }

  object DeadlineExceeded extends GrpcError {
    override def toString = "DEADLINE_EXCEEDED"
    override def errorCode = 4
  }

  object NotFound extends GrpcError {
    override def toString = "NOT_FOUND"
    override def errorCode = 5
  }

  object AlreadyExists extends GrpcError {
    override def toString = "ALREADY_EXISTS"
    override def errorCode = 6
  }

  object PermissionDenied extends GrpcError {
    override def toString = "PERMISSION_DENIED"
    override def errorCode = 7
  }

  object ResourceExhaused extends GrpcError {
    override def toString = "RESOURCE_EXHAUSED"
    override def errorCode = 8
  }

  object FailedPrecondition extends GrpcError {
    override def toString = "FAILED_PRECONDITION"
    override def errorCode = 9
  }

  object Aborted extends GrpcError {
    override def toString = "ABORTED"
    override def errorCode = 10
  }

  object OutOfRange extends GrpcError {
    override def toString = "OUT_OF_RANGE"
    override def errorCode = 11
  }

  object Unimplemented extends GrpcError {
    override def toString = "UNIMPLEMENTED"
    override def errorCode = 12
  }

  object Internal extends GrpcError {
    override def toString = "INTERNAL"
    override def errorCode = 13
  }

  object Unavailable extends GrpcError {
    override def toString = "UNAVAILABLE"
    override def errorCode = 14
  }

  object DataLoss extends GrpcError {
    override def toString = "DATA_LOSS"
    override def errorCode = 15
  }

  object Unauthenticated extends GrpcError {
    override def toString = "UNAUTHENTICATED"
    override def errorCode = 16
  }

  /**
   * Maps an HTTP/2 reset response to a gRPC transport error.
   */
  def fromRst(rst: h2.Reset): GrpcError = {
    rst match {
      case h2.Reset.NoError => GrpcError.NoError
      case h2.Reset.ProtocolError => GrpcError.Internal
      case h2.Reset.InternalError => GrpcError.Internal
      case h2.Reset.FlowControlError => GrpcError.Internal
      case h2.Reset.Cancel => GrpcError.Internal
      // TODO: Reset.Closed can also be a FRAME_SIZE_ERROR
      case h2.Reset.Closed => GrpcError.Internal
      case h2.Reset.Refused => GrpcError.Unavailable
      case h2.Reset.DoesNotExist => GrpcError.Unimplemented
      case h2.Reset.EnhanceYourCalm => GrpcError.ResourceExhaused
      case h2.Reset.CompressionError => GrpcError.Internal
      case _ => GrpcError.Internal
    }
  }

  /**
   * Maps a gRPC error to an HTTP/2 Reset.
   */
  def toRst(grpcError: GrpcError): h2.Reset = {
    grpcError match {
      case GrpcError.NoError => h2.Reset.NoError
      case GrpcError.Unavailable => h2.Reset.Refused
      case GrpcError.ResourceExhaused => h2.Reset.EnhanceYourCalm
      case _ => h2.Reset.InternalError
    }
  }
}