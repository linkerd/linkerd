package io.buoyant.router.h2

import com.twitter.concurrent.AsyncQueue
import com.twitter.conversions.time._
import com.twitter.finagle.Service
import com.twitter.finagle.buoyant.h2.service.{H2Classifier, H2ReqRep, H2ReqRepFrame}
import com.twitter.finagle.buoyant.h2.{Frame, Headers, Request, Response, Status, Stream}
import com.twitter.finagle.service.{ResponseClass, RetryBudget}
import com.twitter.finagle.stats.{InMemoryStatsReceiver, StatsReceiver}
import com.twitter.finagle.util.DefaultTimer
import com.twitter.io.Buf
import com.twitter.util._
import io.buoyant.test.FunSuite
import scala.{Stream => SStream}

class ClassifiedRetryFilterTest extends FunSuite {

  val classifier: H2Classifier = new H2Classifier {
    override val responseClassifier: PartialFunction[H2ReqRep, ResponseClass] = {
      case H2ReqRep(_, Throw(_)) => ResponseClass.NonRetryableFailure
      case H2ReqRep(_, Return(rsp)) if rsp.headers.get("retry") == Some("true") => ResponseClass.RetryableFailure
      case H2ReqRep(_, Return(rsp)) if rsp.headers.get("retry") == Some("false") => ResponseClass.NonRetryableFailure
    }

    override val streamClassifier: PartialFunction[H2ReqRepFrame, ResponseClass] = {
      case H2ReqRepFrame(_, Return((_, Some(Return(f: Frame.Trailers))))) if f.get("retry") == Some("true") =>
        ResponseClass.RetryableFailure
      case H2ReqRepFrame(_, Return((_, Some(Return(f: Frame.Trailers))))) if f.get("retry") == Some("false") =>
        ResponseClass.NonRetryableFailure
      case _ =>
        ResponseClass.Success
    }
  }
  implicit val timer = DefaultTimer

  def filter(stats: StatsReceiver) = new ClassifiedRetryFilter(
    stats,
    classifier,
    SStream.continually(0.millis),
    RetryBudget.Infinite
  )

  def read(stream: Stream): Future[(Buf, Option[Frame.Trailers])] = {
    if (stream.isEmpty) Future.exception(new IllegalStateException("empty stream"))
    else stream.read().flatMap {
      case f: Frame.Data if f.isEnd =>
        f.release()
        Future.value((f.buf, None))
      case f: Frame.Trailers =>
        f.release()
        Future.value((Buf.Empty, Some(f)))
      case f: Frame.Data =>
        f.release()
        read(stream).map { case (next, trailers) => (f.buf.concat(next), trailers) }
    }
  }

  def readStr(stream: Stream): Future[String] = read(stream).map {
    case (buf, _) =>
      Buf.Utf8.unapply(buf).get
  }

  class TestService(tries: Int = 3) extends Service[Request, Response] {
    @volatile var i = 0

    override def apply(request: Request): Future[Response] = {
      readStr(request.stream).map { str =>
        i += 1
        assert(str == "hello")
        val rspQ = new AsyncQueue[Frame]()
        rspQ.offer(Frame.Data("good", eos = false))
        rspQ.offer(Frame.Data("bye", eos = false))
        rspQ.offer(Frame.Trailers("retry" -> (i != tries).toString, "i" -> i.toString))
        Response(Status.Ok, Stream(rspQ))
      }
    }
  }

  test("retries") {
    val reqQ = new AsyncQueue[Frame]
    reqQ.offer(Frame.Data("hel", eos = false))
    reqQ.offer(Frame.Data("lo", eos = true))
    val reqStream = Stream(reqQ)
    val req = Request(Headers.empty, reqStream)

    val stats = new InMemoryStatsReceiver

    val svc = filter(stats).andThen(new TestService())

    val rsp = await(svc(req))

    val (buf, Some(trailers)) = await(read(rsp.stream))
    assert(Buf.Utf8("goodbye") == buf)
    assert(trailers.get("i") == Some("3"))
    assert(trailers.get("retry") == Some("false"))

    assert(stats.counters(Seq("retries", "total")) == 2)
    assert(stats.stats(Seq("retries", "per_request")) == Seq(2f))
    assert(stats.counters.get(Seq("retries", "request_stream_too_long")) == None)
    assert(stats.counters.get(Seq("retries", "response_stream_too_long")) == None)
    assert(stats.counters.get(Seq("retries", "classification_timeout")) == None)
  }

  test("response not retryable") {
    val reqQ = new AsyncQueue[Frame]
    reqQ.offer(Frame.Data("hel", eos = false))
    reqQ.offer(Frame.Data("lo", eos = true))
    val reqStream = Stream(reqQ)
    val req = Request(Headers.empty, reqStream)

    val stats = new InMemoryStatsReceiver

    val svc = filter(stats).andThen(new TestService(tries = 1))

    val rsp = await(svc(req))

    val (buf, Some(trailers)) = await(read(rsp.stream))
    assert(Buf.Utf8("goodbye") == buf)
    assert(trailers.get("i") == Some("1"))
    assert(trailers.get("retry") == Some("false"))

    assert(stats.counters.get(Seq("retries", "total")) == None)
    assert(stats.stats(Seq("retries", "per_request")) == Seq(0f))
    assert(stats.counters.get(Seq("retries", "request_stream_too_long")) == None)
    assert(stats.counters.get(Seq("retries", "response_stream_too_long")) == None)
    assert(stats.counters.get(Seq("retries", "classification_timeout")) == None)
  }

  test("request stream too long to retry") {
    val reqQ = new AsyncQueue[Frame]
    reqQ.offer(Frame.Data("hel", eos = false))
    reqQ.offer(Frame.Data("lo", eos = true))
    val reqStream = Stream(reqQ)
    val req = Request(Headers.empty, reqStream)

    val stats = new InMemoryStatsReceiver

    val svc = new ClassifiedRetryFilter(
      stats,
      classifier,
      SStream.continually(0.millis),
      RetryBudget.Infinite,
      requestBufferSize = 3
    ).andThen(new TestService())

    val rsp = await(svc(req))

    val (buf, Some(trailers)) = await(read(rsp.stream))
    assert(Buf.Utf8("goodbye") == buf)
    assert(trailers.get("i") == Some("1"))
    assert(trailers.get("retry") == Some("true")) // response is retryable but req stream too long

    assert(stats.counters.get(Seq("retries", "total")) == None)
    assert(stats.stats(Seq("retries", "per_request")) == Seq(0f))
    assert(stats.counters(Seq("retries", "request_stream_too_long")) == 1)
    assert(stats.counters.get(Seq("retries", "response_stream_too_long")) == None)
    assert(stats.counters.get(Seq("retries", "classification_timeout")) == None)
  }

  test("response stream too long to retry") {
    val reqQ = new AsyncQueue[Frame]
    reqQ.offer(Frame.Data("hel", eos = false))
    reqQ.offer(Frame.Data("lo", eos = true))
    val reqStream = Stream(reqQ)
    val req = Request(Headers.empty, reqStream)

    val stats = new InMemoryStatsReceiver

    val svc = new ClassifiedRetryFilter(
      stats,
      classifier,
      SStream.continually(0.millis),
      RetryBudget.Infinite,
      responseBufferSize = 4
    ).andThen(new TestService())

    val rsp = await(svc(req))

    val (buf, Some(trailers)) = await(read(rsp.stream))
    assert(Buf.Utf8("goodbye") == buf)
    assert(trailers.get("i") == Some("1"))
    assert(trailers.get("retry") == Some("true")) // response is retryable but response stream too long

    assert(stats.counters.get(Seq("retries", "total")) == None)
    assert(stats.stats(Seq("retries", "per_request")) == Seq(0f))
    assert(stats.counters.get(Seq("retries", "request_stream_too_long")) == None)
    assert(stats.counters(Seq("retries", "response_stream_too_long")) == 1)
    assert(stats.counters.get(Seq("retries", "classification_timeout")) == None)
  }

  test("early classification") {

    val rspQ = new AsyncQueue[Frame]()

    val stats = new InMemoryStatsReceiver

    val svc = filter(stats).andThen(Service.mk { req: Request =>
      Future.value(Response(Headers("retry" -> "false", ":status" -> "200"), Stream(rspQ)))
    })

    // if early classification is possible, the response should not be buffered
    val rsp = await(svc(Request(Headers.empty, Stream.empty())))

    rspQ.offer(Frame.Data("foo", eos = true))
    val frame = await(rsp.stream.read()).asInstanceOf[Frame.Data]
    assert(frame.buf == Buf.Utf8("foo"))
    await(frame.release())

    assert(stats.counters.get(Seq("retries", "total")) == None)
    assert(stats.stats(Seq("retries", "per_request")) == Seq(0f))
    assert(stats.counters.get(Seq("retries", "request_stream_too_long")) == None)
    assert(stats.counters.get(Seq("retries", "response_stream_too_long")) == None)
    assert(stats.counters.get(Seq("retries", "classification_timeout")) == None)
  }

  test("classification timeout") {

    val rspQ = new AsyncQueue[Frame]()

    val stats = new InMemoryStatsReceiver
    val timer = new MockTimer

    val svc = new ClassifiedRetryFilter(
      stats,
      classifier,
      SStream.continually(0.millis),
      RetryBudget.Infinite,
      classificationTimeout = 1.second
    )(timer).andThen(Service.mk { req: Request =>
      Future.value(Response(Status.Ok, Stream(rspQ)))
    })

    Time.withCurrentTimeFrozen { tc =>

      val rspF = svc(Request(Headers.empty, Stream.empty()))

      assert(!rspF.isDefined)

      tc.advance(1.second)
      timer.tick()

      val rsp = await(rspF)
      rspQ.offer(Frame.Data("foo", eos = true))
      val frame = await(rsp.stream.read()).asInstanceOf[Frame.Data]
      assert(frame.buf == Buf.Utf8("foo"))
      frame.release()
    }

    assert(stats.counters.get(Seq("retries", "total")) == None)
    assert(stats.stats(Seq("retries", "per_request")) == Seq(0f))
    assert(stats.counters.get(Seq("retries", "request_stream_too_long")) == None)
    assert(stats.counters.get(Seq("retries", "response_stream_too_long")) == None)
    assert(stats.counters(Seq("retries", "classification_timeout")) == 1)
  }
}
