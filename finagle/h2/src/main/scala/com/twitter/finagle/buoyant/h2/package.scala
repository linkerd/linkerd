package com.twitter.finagle.buoyant

import com.twitter.finagle.service.ResponseClass
import com.twitter.util.Try

package object h2 {

  /** Like a [[com.twitter.finagle.service.ReqRep]], but for H2
    *
    * @param request  a H2 [[Request]]
    * @param response a [[Try]] containing either the H2 [[Response]] and the
    *                 final [[Frame]], or an error. the final frame is contained in
    *                 both an [[Option]], so that empty streams can pass [[None]], and
    *                 in a [[Try]], in case the [[Stream]] [[com.twitter.util.Throw Throw]]s.
    */
  // TODO: consider changing this type to `(Request, Try[Response], Try[Option[Frame]])`
  //       so that patterns matching it can be a little bit less gross?
  case class H2ReqRep(request: Request, response: Try[(Response, Try[Option[Frame]])])
  /**
   * A HTTP/2 response classifier is like a standard
   * [[com.twitter.finagle.service.ResponseClassifier ResponseClassifier]],
   * except that it takes the final [[Frame]] in the [[Stream]] as well as
   * a [[Response]] as inputs.
   */
  type H2ResponseClassifier = PartialFunction[H2ReqRep, ResponseClass]
}
