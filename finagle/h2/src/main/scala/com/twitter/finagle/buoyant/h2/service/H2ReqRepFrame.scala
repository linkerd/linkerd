package com.twitter.finagle.buoyant.h2
package service

import com.twitter.finagle.buoyant.h2.service.H2ReqRepFrame.{FinalFrame, RepAndFrame}
import com.twitter.util.{Return, Throw, Try}

case class H2ReqRep(request: Request, response: Try[Response])

object H2ReqRep {
  def unapply(rrf: H2ReqRepFrame): Option[H2ReqRep] =
    rrf match {
      case H2ReqRepFrame(req, Throw(e)) => Some(H2ReqRep(req, Throw(e)))
      case H2ReqRepFrame(req, Return((rep, None))) => Some(H2ReqRep(req, Return(rep)))
      case _ => None
    }
}

/**
 * Like a [[com.twitter.finagle.service.ReqRep]], but for H2
 *
 * @param request  a H2 [[Request]]
 * @param response a [[Try]] containing either the H2 [[Response]] and the
 *                 final [[Frame]], or an error. the final frame is contained in
 *                 both an [[Option]], so that empty streams can pass [[None]], and
 *                 in a [[Try]], in case the [[Stream]] [[com.twitter.util.Throw Throw]]s.
 */
case class H2ReqRepFrame(request: Request, response: Try[RepAndFrame]) {

  private[this] val _rspOnly: RepAndFrame => Response = _._1
  private[this] val _finalFrame: RepAndFrame => FinalFrame = _._2
  /**
   * the [[Response]] part of `this.response`,
   * ignoring the final frame of the response stream
   */
  lazy val responseOnly: Try[Response] = response.map(_rspOnly)

  /**
   * the final [[Frame]] of the response stream
   */
  lazy val finalFrame: Try[FinalFrame] = response.map(_finalFrame)

  override def toString: String = s"H2ReqRep($request, $responseOnly, finalFrame=$finalFrame)"
}

object H2ReqRepFrame {
  // type aliases to minimise typing
  type RepAndFrame = (Response, FinalFrame)
  type FinalFrame = Option[Try[Frame]]

  @inline def apply(
    request: Request,
    response: Try[Response],
    finalFrame: FinalFrame = None
  ): H2ReqRepFrame =
    H2ReqRepFrame(request, response.map { (_, finalFrame) })

}
