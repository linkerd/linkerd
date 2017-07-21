package com.twitter.finagle.buoyant.h2
package service

import com.twitter.finagle.buoyant.h2.service.H2ReqRep.{FinalFrame, RepAndFrame}
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
  extends ReqRep(request, response) {

  /**
   * the [[Response]] part of `this.response`,
   * ignoring the final frame of the response stream
   */
  def responseOnly: Try[Response] = response.map { case (rsp, _) => rsp }

  /**
   * the final [[Frame]] of the response stream
   */
  def finalFrame: FinalFrame = response.map { case (_, f) => f }.getOrElse(None)

  override def toString: String = s"H2ReqRep($request, $responseOnly, finalFrame=$finalFrame)"
}

object H2ReqRep {
  // type aliases to minimise typing
  type RepAndFrame = (Response, Option[Try[Frame]])
  type FinalFrame = Option[Try[Frame]]

  def apply(request: Request, response: Try[RepAndFrame]): H2ReqRep =
    new H2ReqRep(request, response)

  @inline def apply(request: Request, response: Try[Response], finalFrame: FinalFrame = None): H2ReqRep = H2ReqRep(request, response.map(r => (r, finalFrame)))

  @inline def apply(request: Request, err: Throwable): H2ReqRep = H2ReqRep(request, Throw(err))

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
