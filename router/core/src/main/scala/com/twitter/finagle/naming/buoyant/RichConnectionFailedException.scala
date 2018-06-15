package com.twitter.finagle.naming.buoyant

import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle._
import com.twitter.util.Future
import io.buoyant.namer.{DelegateTree, Delegator}
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
  remote: SocketAddress,
  namer: NameInterpreter
) extends Exception(null, null)
  with NoStackTrace
  with SourcedException
  with FailureFlags[RichConnectionFailedException] {

  def withPath(service: Dst.Path): Future[Nothing] = {
    RichConnectionFailedExceptionWithPath(
      service,
      client,
      remote,
      namer
    )
  }

  override private[finagle] def flags = FailureFlags.Retryable

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
  remote: String,
  resolution: Seq[String]
) extends Exception(null, null)
  with NoStackTrace
  with SourcedException
  with FailureFlags[RichConnectionFailedExceptionWithPath] {

  private[this] val addressList = addresses.collect {
    case Address.Inet(isa, _) => isa.toString.stripPrefix("/")
  }.mkString("[", ", ", "]")

  private[this] val resolutionList = resolution.mkString("\n")

  override lazy val exceptionMessage: String =
    s"""Unable to establish connection to $remote.

service name: ${service.path.show}
client name: $client
addresses: $addressList
selected address: ${remote.toString.stripPrefix("/")}
dtab resolution:
$resolutionList
"""

  override private[finagle] def flags = FailureFlags.Retryable

  override protected def copyWithFlags(flags: Long): RichConnectionFailedExceptionWithPath =
    throw new IllegalStateException("Cannot modify flags of RichConnectionFailedExceptionWithPath")
}

object RichConnectionFailedExceptionWithPath {

  def apply(
    path: Dst.Path,
    client: Option[Name.Bound],
    remote: SocketAddress,
    namer: NameInterpreter
  ): Future[Nothing] = {

    val addresses = client match {
      case Some(bound) =>
        bound.addr.changes.toFuture.map {
          case Addr.Bound(a, _) => a
          case _ => Set.empty[Address]
        }
      case None => Future.value(Set.empty[Address])
    }

    val resolution = (namer match {
      case delegator: Delegator =>
        delegator.delegate(path.dtab, path.path).map { tree =>
          DelegateTree.find[Name.Bound](tree, client.contains)
        }
      case _ => Future.None
    }).map {
      case Some(nodes) => nodes.map {
        case (path, "") =>
          s"  ${path.show}"
        case (path, dentry) =>
          s"  ${path.show} ($dentry)"
      }
      case None => Nil
    }

    val clnt = client match {
      case Some(b) =>
        b.id match {
          case id: Path => id.show
          case _ => "unknown"
        }
      case None => "unknown"
    }

    addresses.join(resolution).map {
      case (addrs, res) =>
        new RichConnectionFailedExceptionWithPath(
          path,
          clnt,
          addrs,
          remote.toString.stripPrefix("/"),
          res
        )
    }.flatMap(Future.exception)
  }
}
