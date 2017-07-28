package io.buoyant.linkerd.protocol.h2

import com.twitter.finagle.buoyant.h2._
import com.twitter.finagle.buoyant.h2.service.{H2Classifier, H2Classifiers, H2ReqRep}
import com.twitter.finagle.service.ResponseClass
import com.twitter.finagle.util.LoadService
import com.twitter.finagle.{ChannelClosedException, RequestTimeoutException}
import com.twitter.util._
import io.buoyant.config.Parser
import io.buoyant.linkerd.RouterConfig
import io.buoyant.linkerd.protocol.{H2DefaultSvc, H2Initializer}
import org.scalatest.FunSuite

class H2ClassifiersTest extends FunSuite {

  def testClassifier(
    classifier: H2Classifier,
    method: Method,
    status: Try[Status],
    classification: Option[ResponseClass]
  ) = {
    val req = Request("http", method, "auf", "/", Stream.empty())
    val key = H2ReqRep(req, status.map { Response(_, Stream.empty()) })
    classification match {
      case None =>
        assert(!classifier.responseClassifier.isDefinedAt(key))
      case Some(classification) =>
        assert(classifier.responseClassifier.isDefinedAt(key))
        assert(classifier.responseClassifier(key) == classification)
    }
  }

  val allMethods = Set(
    Method.Connect,
    Method.Delete,
    Method.Get,
    Method.Head,
    Method.Patch,
    Method.Post,
    Method.Put,
    Method.Options,
    Method.Trace
  )

  for (
    (classifier, retryMethods) <- Map(
      H2Classifiers.RetryableIdempotentFailures ->
        Set(Method.Get, Method.Head, Method.Put, Method.Delete, Method.Options, Method.Trace),
      H2Classifiers.RetryableReadFailures ->
        Set(Method.Get, Method.Head, Method.Options, Method.Trace)
    )
  ) {

    for (method <- retryMethods) {
      test(s"$classifier: ignores $method 1XX-4XX") {
        for (code <- 100 to 499)
          testClassifier(classifier, method, Return(Status(code)), None)
      }

      test(s"$classifier: retries $method 5XX") {
        for (code <- 500 to 599)
          testClassifier(
            classifier,
            method,
            Return(Status(code)),
            Some(ResponseClass.RetryableFailure)
          )
      }

      test(s"$classifier: retries $method timeout") {
        testClassifier(
          classifier,
          method,
          Throw(new TimeoutException("timeout")),
          Some(ResponseClass.RetryableFailure)
        )
      }

      test(s"$classifier: retries $method request timeout") {
        testClassifier(
          classifier,
          method,
          Throw(new RequestTimeoutException(Duration.Zero, "timeout")),
          Some(ResponseClass.RetryableFailure)
        )
      }

      test(s"$classifier: retries $method channel closed") {
        testClassifier(
          classifier,
          method,
          Throw(new ChannelClosedException),
          Some(ResponseClass.RetryableFailure)
        )
      }

      test(s"$classifier: retries $method errors") {
        testClassifier(
          classifier,
          method,
          Throw(new Exception),
          None
        )
      }
    }

    for (method <- allMethods -- retryMethods) {
      test(s"$classifier: ignores $method responses") {
        for (code <- 100 to 599)
          testClassifier(classifier, method, Return(Status(code)), None)
      }

      test(s"$classifier: fails $method error") {
        testClassifier(classifier, method, Throw(new Exception), None)
      }
    }
  }

  {
    val classifier = H2Classifiers.NonRetryableServerFailures
    for (method <- allMethods) {
      test(s"$classifier: ignores $method 1XX-4XX") {
        for (code <- 100 to 499)
          testClassifier(classifier, method, Return(Status(code)), None)
      }

      test(s"$classifier: fails $method 5XX") {
        for (code <- 500 to 599)
          testClassifier(
            classifier,
            method,
            Return(Status(code)),
            Some(ResponseClass.NonRetryableFailure)
          )
      }
    }
  }

  for (
    init <- Seq(
      NonRetryable5XXInitializer,
      RetryableIdempotent5XXInitializer,
      RetryableRead5XXInitializer
    )
  ) {
    val kind = init.configId

    test(s"loads $kind") {
      assert(LoadService[H2ClassifierInitializer]().exists(_.configId == kind))
    }

    test(s"parse router with $kind") {
      val yaml =
        s"""|protocol: h2
            |service:
            |  responseClassifier:
            |    kind: $kind
            |servers:
            |- port: 0
            |""".stripMargin
      val mapper = Parser.objectMapper(yaml, Iterable(Seq(H2Initializer), Seq(init)))
      val router = mapper.readValue[RouterConfig](yaml)
      assert(router.service.get.asInstanceOf[H2DefaultSvc]._h2ResponseClassifier.isDefined)
      assertThrows[UnsupportedOperationException] {
        router.service.get.asInstanceOf[H2DefaultSvc].responseClassifierConfig
      }
    }
  }
}
