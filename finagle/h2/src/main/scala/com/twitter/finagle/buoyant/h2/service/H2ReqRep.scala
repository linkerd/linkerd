package com.twitter.finagle.buoyant.h2
package service

import com.twitter.finagle.buoyant.h2.service.H2ReqRep.RepAndFrame
import com.twitter.finagle.service.ReqRep
import com.twitter.util.{Return, Throw, Try}

/**
 * Like a [[com.twitter.finagle.service.ReqRep]], but for H2
 *
 * @param request  a H2 [[Request]]
 * @param response a [[Try]] containing either the H2 [[Response]] and the
 *                 final [[Frame]], or an error. the final frame is contained in
 *                 both an [[Option]], so that empty streams can pass [[None]], and
 *                 in a [[Try]], in case the [[Stream]] [[com.twitter.util.Throw Throw]]s.
 */
class H2ReqRep(override val request: Request, override val response: Try[RepAndFrame])
  extends ReqRep(request, response)

object H2ReqRep {
  type RepAndFrame = (Response, Option[Try[Frame]])

  @inline def apply(request: Request, response: Try[RepAndFrame]): H2ReqRep =
    new H2ReqRep(request, response)

  def unapply(reqrep: ReqRep): Option[(Request, Try[RepAndFrame])] =
    reqrep match {
      // unfortunately, we need to destructure Literally The Entire Thing,
      // because we can't match any of the inner Trys with type patterns
      // like  `Option[Try[Frame]]` – the type parameters are eliminated
      // by type erasure.
      //
      // so this pattern match is *much* uglier than I had originally
      // hoped it would be. thanks, Sun, for not initially designing
      // the JVM with support for generics in mind...
      case ReqRep(req: Request, Return((rsp: Response, Some(Return(frame: Frame))))) =>
        Some((req, Return((rsp, Some(Return(frame))))))
      case ReqRep(req: Request, Return((rsp: Response, Some(Throw(e))))) =>
        Some((req, Return((rsp, Some(Throw(e))))))
      case ReqRep(req: Request, Return((rsp: Response, None))) =>
        Some((req, Return((rsp, None))))
      case ReqRep(req: Request, Throw(e)) =>
        Some((req, Throw(e)))
      case _ => None
    }
}
