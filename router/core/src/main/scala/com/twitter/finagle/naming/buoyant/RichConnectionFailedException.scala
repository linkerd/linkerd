package com.twitter.finagle.naming.buoyant

import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle._
import com.twitter.util.Future
import java.net.SocketAddress
import scala.util.control.NoStackTrace

/**
 * An exception indicating that a connection could not be established to a remote peer.  This
 * exception also includes additional information about the routing context for the purposes of
 * displaying a more useful error message.  Since this exception is typically thrown in a router's
 * client stack, the service name is not available and the `withPath` method can be used to
 * augment this exception with the service name.
 */
case class RichConnectionFailedException(
  client: Option[Name.Bound],
  remote: SocketAddress
) extends Exception(null, null)
  with NoStackTrace
  with SourcedException
  with FailureFlags[RichConnectionFailedException] {

  def withPath(service: Dst.Path): Future[Nothing] = {
    RichConnectionFailedExceptionWithPath(
      service,
      client,
      remote
    )
  }

  override def flags = FailureFlags.Retryable

  override protected def copyWithFlags(flags: Long): RichConnectionFailedException =
    throw new IllegalStateException("Cannot modify flags of RichConnectionFailedException")
}

/**
 * RichConnectionFailedExceptionWithPath is a RichConnectionFailedException augmented with the
 * service name and can be formatted as a user facing error message.
 */
class RichConnectionFailedExceptionWithPath(
  service: Dst.Path,
  client: String,
  addresses: Set[Address],
  remote: String
) extends Exception(null, null)
  with NoStackTrace
  with SourcedException
  with FailureFlags[RichConnectionFailedExceptionWithPath] {

  private[this] val addressList = addresses.collect {
    case Address.Inet(isa, _) => isa.toString.stripPrefix("/")
  }.mkString("[", ", ", "]")

  override lazy val exceptionMessage: String =
    s"""Unable to establish connection to $remote.

service name: ${service.path.show}
client name: $client
addresses: $addressList
selected address: ${remote.toString.stripPrefix("/")}
"""

  override def flags = FailureFlags.Retryable

  override protected def copyWithFlags(flags: Long): RichConnectionFailedExceptionWithPath =
    throw new IllegalStateException("Cannot modify flags of RichConnectionFailedExceptionWithPath")
}

object RichConnectionFailedExceptionWithPath {

  def apply(
    path: Dst.Path,
    client: Option[Name.Bound],
    remote: SocketAddress
  ): Future[Nothing] = {

    val addresses = client match {
      case Some(bound) =>
        bound.addr.changes.toFuture.map {
          case Addr.Bound(a, _) => a
          case _ => Set.empty[Address]
        }
      case None => Future.value(Set.empty[Address])
    }

    val clnt = client match {
      case Some(b) =>
        b.id match {
          case id: Path => id.show
          case _ => "unknown"
        }
      case None => "unknown"
    }

    addresses.map { addrs =>
      new RichConnectionFailedExceptionWithPath(
        path,
        clnt,
        addrs,
        remote.toString.stripPrefix("/")
      )
    }.flatMap(Future.exception)
  }
}
