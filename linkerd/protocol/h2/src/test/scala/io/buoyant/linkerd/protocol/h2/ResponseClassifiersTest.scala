package io.buoyant.linkerd.protocol.h2

import com.twitter.io.Buf
import com.twitter.finagle.{ChannelClosedException, Failure, RequestTimeoutException}
import com.twitter.finagle.buoyant.h2._
import com.twitter.finagle.service.{ResponseClass, ReqRep, ResponseClassifier}
import com.twitter.finagle.util.LoadService
import com.twitter.util.{Duration, Return, Throw, Try, TimeoutException}
import io.buoyant.config.Parser
import io.buoyant.linkerd._
import io.buoyant.linkerd.protocol.{H2DefaultSvc, H2Initializer}
import org.scalatest.FunSuite

class ResponseClassifiersTest extends FunSuite {

  def testClassifier(
    classifier: ResponseClassifier,
    method: Method,
    status: Try[Status],
    classification: Option[ResponseClass]
  ) = {
    val req = Request("http", method, "auf", "/", Stream.empty())
    val key = ReqRep(req, status.map(Response(_, Stream.empty())))
    classification match {
      case None =>
        assert(!classifier.isDefinedAt(key))
      case Some(classification) =>
        assert(classifier.isDefinedAt(key))
        assert(classifier(key) == classification)
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
      ResponseClassifiers.RetryableIdempotentFailures ->
        Set(Method.Get, Method.Head, Method.Put, Method.Delete, Method.Options, Method.Trace),
      ResponseClassifiers.RetryableReadFailures ->
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
    val classifier = ResponseClassifiers.NonRetryableServerFailures
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
      assert(LoadService[ResponseClassifierInitializer]().exists(_.configId == kind))
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
      assert(router.service.get.asInstanceOf[H2DefaultSvc]._responseClassifier.isDefined)
    }
  }

  test("NonRetryableStream: makes RetryableFailures NonRetryableFailures when chunked") {
    val classifier = ResponseClassifiers.NonRetryableStream { case ReqRep(_, _) => ResponseClass.RetryableFailure }
    val req = Request("http", Method.Get, "auf", "/", Stream.const(Buf.Utf8("yo")))
    val rsp = Response(Status.EatMyShorts, Stream.empty())
    Response(Status.Ok, Stream.empty())
    assert(classifier(ReqRep(req, Return(rsp))) == ResponseClass.NonRetryableFailure)
  }

  test("NonRetryableStream: does not change RetryableFailures when not chunked") {
    val classifier = ResponseClassifiers.NonRetryableStream { case ReqRep(_, _) => ResponseClass.RetryableFailure }
    val req = Request("http", Method.Get, "auf", "/", Stream.empty())
    val rsp = Response(Status.EatMyShorts, Stream.empty())
    assert(classifier(ReqRep(req, Return(rsp))) == ResponseClass.RetryableFailure)
  }
}
