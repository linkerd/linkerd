package com.twitter.finagle.buoyant.h2

import com.twitter.finagle.service.ResponseClass
import com.twitter.util.Return

object H2ResponseClassifier {

  /**
    * Create a [[H2ResponseClassifier]] with the given name for its `toString`.
    *
    * @note be careful when `underlying` composes other `H2ResponseClassifiers`
    *       which are not total.
    */
  def named(name: String)(underlying: H2ResponseClassifier): H2ResponseClassifier =
    new H2ResponseClassifier {
      def isDefinedAt(reqRep: H2ReqRep): Boolean = underlying.isDefinedAt(reqRep)
      def apply(reqRep: H2ReqRep): ResponseClass = underlying(reqRep)
      override def toString: String = name
    }

  /**
    * a simple total [[H2ResponseClassifier]] that classifies responses as successful
    * if and only if the response and response stream are both successful
    */
  val Default: H2ResponseClassifier = named("DefaultH2ResponseClassifier") {
    case H2ReqRep(_, Return((_, Return(_)))) => ResponseClass.Success
    case _ => ResponseClass.NonRetryableFailure
  }

  /**
    * an [[H2ResponseClassifier]] that classifies responses as successful if
    * the response and response stream are both successful, and the status code
    * is less than 500
    */
  val Not5xxH2ResponseClassifier: H2ResponseClassifier =
    named("Not5xxH2ResponseClassifier") {
      case H2ReqRep(_, Return((response, Return(_))))
        if response.status.code < 500 => ResponseClass.Success
    }



}
