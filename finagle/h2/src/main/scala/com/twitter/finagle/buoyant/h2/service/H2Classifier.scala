package com.twitter.finagle.buoyant.h2.service

import com.twitter.finagle.service.ResponseClass

/**
 * H2StreamClassifier is similar to Finagle's ResponseClassifier type, but can also classify based
 * on the final Frame of the Response stream.  It is H2 specific.  When a Response future
 * completes, the responseClassifier PartialFunction can be checked.  If responseClassifier is
 * defined then the response can be immediately classified, just as with Finagle's
 * ResponseClassifier.  If responseClassifier is not defined at that input, we must wait until the
 * final Frame of the response stream has been read and then use the streamClassifier
 * PartialFunction to classify the response.
 */
trait H2Classifier { self =>
  def streamClassifier: PartialFunction[H2ReqRepFrame, ResponseClass] = PartialFunction.empty
  def responseClassifier: PartialFunction[H2ReqRep, ResponseClass] = PartialFunction.empty

  def orElse(next: H2Classifier): H2Classifier = new H2Classifier {
    override lazy val streamClassifier: PartialFunction[H2ReqRepFrame, ResponseClass] = self.streamClassifier.orElse(next.streamClassifier)
    override lazy val responseClassifier: PartialFunction[H2ReqRep, ResponseClass] = self.responseClassifier.orElse(next.responseClassifier)
    override def toString: String = s"$self orElse $next"
  }
}
