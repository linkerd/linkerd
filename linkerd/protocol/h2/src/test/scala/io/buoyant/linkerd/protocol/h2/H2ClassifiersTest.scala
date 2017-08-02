package io.buoyant.linkerd.protocol.h2

import com.twitter.finagle.buoyant.h2._
import com.twitter.finagle.buoyant.h2.service.{H2Classifier, H2ReqRep, H2ReqRepFrame}
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
    frame: Option[Try[Frame]],
    classification: Option[ResponseClass]
  ) = {
    val req = Request("http", method, "auf", "/", Stream.empty())
    val reqRep = H2ReqRep(req, status.map { Response(_, Stream.empty()) })
    val reqRepFrame = H2ReqRepFrame(req, status.map { Response(_, Stream.empty()) -> frame })

    val rc = classifier.responseClassifier.lift(reqRep).orElse(classifier.streamClassifier.lift(reqRepFrame))
    assert(rc == classification)
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
      new RetryableIdempotent5XXConfig().mk ->
        Set(Method.Get, Method.Head, Method.Put, Method.Delete, Method.Options, Method.Trace),
      new RetryableRead5XXConfig().mk ->
        Set(Method.Get, Method.Head, Method.Options, Method.Trace)
    )
  ) {

    for (method <- retryMethods) {
      test(s"$classifier: $method 1XX-4XX success") {
        for (code <- 100 to 499)
          testClassifier(classifier, method, Return(Status(code)), None, Some(ResponseClass.Success))
      }

      test(s"$classifier: retries $method 5XX") {
        for (code <- 500 to 599)
          testClassifier(
            classifier,
            method,
            Return(Status(code)),
            None,
            Some(ResponseClass.RetryableFailure)
          )
      }

      test(s"$classifier: retries $method timeout") {
        testClassifier(
          classifier,
          method,
          Throw(new TimeoutException("timeout")),
          None,
          Some(ResponseClass.RetryableFailure)
        )
      }

      test(s"$classifier: retries $method stream timeout") {
        testClassifier(
          classifier,
          method,
          Return(Status(200)),
          Some(Throw(new TimeoutException("timeout"))),
          Some(ResponseClass.RetryableFailure)
        )
      }

      test(s"$classifier: retries $method request timeout") {
        testClassifier(
          classifier,
          method,
          Throw(new RequestTimeoutException(Duration.Zero, "timeout")),
          None,
          Some(ResponseClass.RetryableFailure)
        )
      }

      test(s"$classifier: retries $method channel closed") {
        testClassifier(
          classifier,
          method,
          Throw(new ChannelClosedException),
          None,
          Some(ResponseClass.RetryableFailure)
        )
      }

      test(s"$classifier: retries $method stream channel closed") {
        testClassifier(
          classifier,
          method,
          Return(Status(200)),
          Some(Throw(new ChannelClosedException)),
          Some(ResponseClass.RetryableFailure)
        )
      }

      test(s"$classifier: does not retry $method errors") {
        testClassifier(
          classifier,
          method,
          Throw(new Exception),
          None,
          Some(ResponseClass.NonRetryableFailure)
        )
      }

      test(s"$classifier: does not retry $method stream errors") {
        testClassifier(
          classifier,
          method,
          Return(Status(200)),
          Some(Throw(new Exception)),
          Some(ResponseClass.NonRetryableFailure)
        )
      }
    }

    for (method <- allMethods -- retryMethods) {
      test(s"$classifier: $method responses successful") {
        for (code <- 100 to 499)
          testClassifier(classifier, method, Return(Status(code)), None, Some(ResponseClass.Success))
      }

      test(s"$classifier: $method responses stream successful") {
        for (code <- 100 to 499)
          testClassifier(classifier, method, Return(Status(code)), Some(Return(Frame.Trailers())), Some(ResponseClass.Success))
      }

      test(s"$classifier: $method does not retry responses") {
        for (code <- 500 to 599)
          testClassifier(classifier, method, Return(Status(code)), None, Some(ResponseClass.NonRetryableFailure))
      }

      test(s"$classifier: fails $method error") {
        testClassifier(classifier, method, Throw(new Exception), None, Some(ResponseClass.NonRetryableFailure))
      }

      test(s"$classifier: fails $method stream error") {
        testClassifier(classifier, method, Return(Status(200)), Some(Throw(new Exception)), Some(ResponseClass.NonRetryableFailure))
      }
    }
  }

  {
    val classifier = new NonRetryable5XXConfig().mk
    for (method <- allMethods) {
      test(s"$classifier: $method 1XX-4XX success") {
        for (code <- 100 to 499)
          testClassifier(classifier, method, Return(Status(code)), None, Some(ResponseClass.Success))
      }

      test(s"$classifier: $method 1XX-4XX stream success") {
        for (code <- 100 to 499)
          testClassifier(classifier, method, Return(Status(code)), Some(Return(Frame.Trailers())), Some(ResponseClass.Success))
      }

      test(s"$classifier: $method 1XX-4XX stream failure") {
        for (code <- 100 to 499)
          testClassifier(classifier, method, Return(Status(code)), Some(Throw(new Exception)), Some(ResponseClass.NonRetryableFailure))
      }

      test(s"$classifier: fails $method 5XX") {
        for (code <- 500 to 599)
          testClassifier(
            classifier,
            method,
            Return(Status(code)),
            None,
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
      assert(router.service.get.asInstanceOf[H2DefaultSvc]._h2Classifier.isDefined)
      assertThrows[UnsupportedOperationException] {
        router.service.get.asInstanceOf[H2DefaultSvc].responseClassifierConfig
      }
    }
  }
}
