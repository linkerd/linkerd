package com.twitter.finagle.buoyant

import com.twitter.finagle.service.ResponseClass
import com.twitter.util.Try

package object h2 {
  case class H2ReqRep(request: Request, response: Try[(Response, Try[Frame])])
  /**
   * A HTTP/2 response classifier is like a standard
   * [[com.twitter.finagle.service.ResponseClassifier ResponseClassifier]],
   * except that it takes the final [[Frame]] in the [[Stream]] as well as
   * a [[Response]] as inputs.
   */
  type H2ResponseClassifier = PartialFunction[H2ReqRep, ResponseClass]
}
