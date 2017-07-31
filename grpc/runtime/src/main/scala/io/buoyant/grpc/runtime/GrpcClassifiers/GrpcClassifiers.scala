package io.buoyant.grpc.runtime

import com.twitter.finagle.buoyant.h2.service.{H2Classifier, H2ReqRep, H2ReqRepFrame}
import com.twitter.finagle.service.ResponseClass

/**
  * [[H2Classifier]]s for gRPC
  */
object GrpcClassifiers {

  /**
    * [[H2Classifier]] for idempotent gRPC requests
    */
  object IdempotentRequest extends H2Classifier {
    override def streamClassifier: PartialFunction[H2ReqRepFrame, ResponseClass] = ???
    override def responseClassifier: PartialFunction[H2ReqRep, ResponseClass] = ???
  }

  /**
    * [[H2Classifier]] for non-idempotent gRPC requests
    */
  object NonIdempotentRequest extends H2Classifier {
    override def streamClassifier: PartialFunction[H2ReqRepFrame, ResponseClass] = ???
    override def responseClassifier: PartialFunction[H2ReqRep, ResponseClass] = ???
  }
}
