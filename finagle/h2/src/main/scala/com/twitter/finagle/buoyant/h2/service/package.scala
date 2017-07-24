package com.twitter.finagle.buoyant.h2

import com.twitter.finagle.service.ResponseClass

package object service {
  type H2StreamClassifier = PartialFunction[H2ReqRep, ResponseClass]
}
