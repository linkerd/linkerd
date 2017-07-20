package io.buoyant.grpc.runtime

import com.twitter.finagle.buoyant.h2
import scala.util.control.NoStackTrace

sealed abstract class GrpcStatus(val code: Int) extends NoStackTrace {
  def message: String

  def toReset: h2.Reset = GrpcStatus.toReset(this)
  def toTrailers: h2.Frame.Trailers = GrpcStatus.toTrailers(this)
}

object GrpcStatus {

  case class Ok(message: String = "") extends GrpcStatus(0)
  case class Canceled(message: String = "") extends GrpcStatus(1)
  case class Unknown(message: String = "") extends GrpcStatus(2)
  case class InvalidArgument(message: String = "") extends GrpcStatus(3)
  case class DeadlineExceeded(message: String = "") extends GrpcStatus(4)
  case class NotFound(message: String = "") extends GrpcStatus(5)
  case class AlreadyExists(message: String = "") extends GrpcStatus(6)
  case class PermissionDenied(message: String = "") extends GrpcStatus(7)
  case class Unauthenticated(message: String = "") extends GrpcStatus(16)
  case class ResourceExhausted(message: String = "") extends GrpcStatus(8)
  case class FailedPrecondition(message: String = "") extends GrpcStatus(9)
  case class Aborted(message: String = "") extends GrpcStatus(10)
  case class OutOfRange(message: String = "") extends GrpcStatus(11)
  case class Unimplemented(message: String = "") extends GrpcStatus(12)
  case class Internal(message: String = "") extends GrpcStatus(13)
  case class Unavailable(message: String = "") extends GrpcStatus(14)
  case class DataLoss(message: String = "") extends GrpcStatus(15)

  case class Other(c: Int, message: String) extends GrpcStatus(c)

  def apply(code: Int, msg: String): GrpcStatus = code match {
    case 0 => Ok(msg)
    case 1 => Canceled(msg)
    case 2 => Unknown(msg)
    case 3 => InvalidArgument(msg)
    case 4 => DeadlineExceeded(msg)
    case 5 => NotFound(msg)
    case 6 => AlreadyExists(msg)
    case 7 => PermissionDenied(msg)
    case 8 => ResourceExhausted(msg)
    case 9 => FailedPrecondition(msg)
    case 10 => Aborted(msg)
    case 11 => OutOfRange(msg)
    case 12 => Unimplemented(msg)
    case 13 => Internal(msg)
    case 14 => Unavailable(msg)
    case 15 => DataLoss(msg)
    case 16 => Unauthenticated(msg)
    case code => Other(code, msg)
  }

  def unapply(s: GrpcStatus): Option[(Int, String)] = Some((s.code, s.message))

  def fromReset(rst: h2.Reset): GrpcStatus = rst match {
    case h2.Reset.NoError |
      h2.Reset.ProtocolError |
      h2.Reset.InternalError => Internal()
    case h2.Reset.Refused => Unavailable()
    case h2.Reset.EnhanceYourCalm => ResourceExhausted("rate limit exceeded")
    case h2.Reset.Cancel => Canceled()
    case h2.Reset.Closed => Unknown()
  }

  private def toReset(status: GrpcStatus): h2.Reset = status match {
    case Internal(_) => h2.Reset.InternalError
    case Unavailable(_) => h2.Reset.Refused
    case ResourceExhausted(_) => h2.Reset.EnhanceYourCalm
    case _ => h2.Reset.Cancel
  }

  def fromTrailers(tlrs: h2.Frame.Trailers): GrpcStatus = {
    val msg = tlrs.get("grpc-message").getOrElse("")
    tlrs.get("grpc-status") match {
      case Some(code) =>
        try GrpcStatus(code.toInt, msg)
        catch { case _: NumberFormatException => GrpcStatus.Unknown(s"bad status code: '$code'") }
      case _ => GrpcStatus.Unknown(msg)
    }
  }

  private def toTrailers(status: GrpcStatus): h2.Frame.Trailers =
    h2.Frame.Trailers(
      "grpc-status" -> status.code.toString,
      "grpc-message" -> status.message
    )

}
