package com.twitter.finagle.naming.buoyant

import com.twitter.finagle._
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.naming.BindingFactory
import com.twitter.util.Future

/**
 * This module catches ConnectionFailedExceptions and rethrows them as
 * RichConnectionFailedExceptions, adding helpful information about the current routing context.
 * This module is typically used in the client stack.
 */
class RichConnectionFailedModule[Request, Response] extends Stack.Module1[BindingFactory.Dest, ServiceFactory[Request, Response]] {

  override def role: Stack.Role = Stack.Role("RichConnectionFailed")

  override def description: String = "Adds routing context to connection exceptions"

  override def make(
    dest: BindingFactory.Dest,
    next: ServiceFactory[Request, Response]
  ): ServiceFactory[Request, Response] = {

    val bound = dest.dest match {
      case b: Name.Bound => Some(b)
      case _ => None
    }

    new ServiceFactoryProxy(next) {
      override def apply(conn: ClientConnection): Future[Service[Request, Response]] = {
        self(conn).rescue {
          case Failure(Some(e: ConnectionFailedException)) =>
            Future.exception(RichConnectionFailedException(
              bound,
              e.remoteAddress
            ))
        }
      }
    }
  }
}

/**
 * This module catches RichConnectionFailedExceptions and rethrows them as
 * RichConnectionFailedWithPathExceptions, adding the current service path to the exception.  The
 * result is an exception that can be formatted into a user facing error message.  This module
 * should be used in the path stack, above client stacks that use the RichConnectionFailedModule.
 */
class RichConnectionFailedPathModule[Request, Response] extends Stack.Module1[Dst.Path, ServiceFactory[Request, Response]] {

  override def role: Stack.Role = Stack.Role("RichConnectionFailedWithPath")

  override def description: String = "Adds path context to connection exceptions"

  override def make(
    path: Dst.Path,
    next: ServiceFactory[Request, Response]
  ): ServiceFactory[Request, Response] = {

    new ServiceFactoryProxy(next) {
      override def apply(conn: ClientConnection): Future[Service[Request, Response]] = {
        self(conn).rescue {
          case e: RichConnectionFailedException =>
            e.withPath(path)
        }
      }
    }
  }
}
