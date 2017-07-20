package com.twitter.finagle.buoyant.h2

import com.twitter.finagle.service.ResponseClass
import com.twitter.util.{Return, Throw}

object H2ResponseClassifier {
  private[this] def is500(r: Response): Boolean =
    r.status.code >= 500 && r.status.code <= 599

  /**
   * Create a [[H2ResponseClassifier]] with the given name for its `toString`.
   */
  def named(name: String)(underlying: H2ResponseClassifier): H2ResponseClassifier =
    new H2ResponseClassifier {
      def isDefinedAt(reqRep: H2ReqRep): Boolean = underlying.isDefinedAt(reqRep)
      def apply(reqRep: H2ReqRep): ResponseClass = underlying(reqRep)
      override def toString: String = name
    }

  /**
   * a simple [[H2ResponseClassifier]] that classifies responses as failures
    * if an exception was [[com.twitter.util.Throw]]n
   */
  val ExceptionsAsFailures: H2ResponseClassifier =
    named("ExceptionsAsFailuresH2ResponseClassifier") {
      case H2ReqRep(_, Throw(_)) | H2ReqRep(_, Return((_, Throw(_)))) =>
        ResponseClass.NonRetryableFailure
    }

  /**
   * an [[H2ResponseClassifier]] that classifies responses as failures if
   * the status code was greater than or equal to 500 (server errors)
   */
  val ServerErrorsAsFailures: H2ResponseClassifier =
    named("ServerErrorsAsFailuresH2ResponseClassifier") {
      case H2ReqRep(_, Return((response, Return(_)))) if is500(response) =>
        ResponseClass.NonRetryableFailure
    }

  val AssumeSuccess: H2ResponseClassifier = {
    case _ => ResponseClass.Success
  }

  /**
    * an [[H2ResponseClassifier]] that first tries to classify [[ExceptionsAsFailures]],
    * then tries to classify [[ServerErrorsAsFailures]] and finally
    * [[AssumeSuccess assumes success]] for unclassified responses
    */
  val Default: H2ResponseClassifier =
    named("DefaultH2ResponseClassifier"){
      ExceptionsAsFailures orElse ServerErrorsAsFailures orElse AssumeSuccess
    }


}
