package io.buoyant.router.h2

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.{Service, ServiceFactory, Stack, param}
import com.twitter.finagle.buoyant.h2.{Frame, Method, Request, Response, Status, Stream}
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.util.Future
import io.buoyant.test.{Awaits, FunSuite, StatsAssertions}
import io.buoyant.test.h2.StreamTestUtils._

class StreamTestStatsFilterTest extends FunSuite with Awaits with StatsAssertions {
  private[this] val successCounters = Seq(
    Seq("response", "stream", "stream_success"),
    Seq("request", "stream", "stream_success"),
    Seq("stream", "stream_success"),
    Seq("success")
  )
  private[this] val failureCounters = Seq(
    Seq("response", "stream", "stream_failures"),
    Seq("request", "stream", "stream_failures"),
    Seq("stream", "stream_failures"),
    Seq("failures")
  )
  private[this] val requestCounter = Seq("requests")
  private[this] val allCounters = successCounters ++ failureCounters :+ requestCounter
  private[this] val latencyStats = Seq(
    Seq("response", "stream", "stream_duration_ms"),
    Seq("request", "stream", "stream_duration_ms"),
    Seq("stream", "total_latency_ms"),
    Seq("request_latency_ms")
  )
  private[this] val rspFrameSizeStat = Seq("response", "stream", "data_bytes")
  private[this] val reqFrameSizeStat = Seq("request", "stream", "data_bytes")
  //  val rspFrameCountStat = Seq("response", "stream", "data_frame", "count")
  private[this] val allStats = latencyStats :+ rspFrameSizeStat :+ reqFrameSizeStat
  private[this] val reqStats = allStats.withFilter(_.head != "response")

  private[this] def setup(response: Request => Future[Response]): (InMemoryStatsReceiver, Service[Request, Response]) = {

    val stats = new InMemoryStatsReceiver()
    val svc = Service.mk[Request, Response] { response(_) }
    val stk = StreamStatsFilter.module.toStack(
      Stack.Leaf(StreamStatsFilter.role, ServiceFactory.const(svc))
    )
    val service = await(stk.make(Stack.Params.empty + param.Stats(stats))())

    (stats, service)
  }

  test("increments success counters on success") {

    val (_stats, service) = setup { _ =>
      Future.value(Response(Status.Ok, Stream.const("aaaaaaaaaaaa")))
    }
    implicit val stats = _stats

    // all counters should be empty before firing request
    withClue("before request:") {
      for { counter <- allCounters }
        assertCounter(counter).isEmpty
    }

    val req = Request("http", Method.Get, "hihost", "/", Stream.empty())
    var rsp = await(service(req))
    await(rsp.stream.readToEnd)

    withClue("after first response") {
      for { counter <- successCounters :+ requestCounter }
        assertCounter(counter) is 1
    }

    rsp = await(service(req))
    await(rsp.stream.readToEnd)

    withClue("after second response") {
      for { counter <- successCounters :+ requestCounter }
        assertCounter(counter) is 2
    }
  }

  test("increments success counters on success with empty stream") {

    val (_stats, service) = setup { _ =>
      Future.value(Response(Status.Ok, Stream.empty()))
    }

    implicit val stats = _stats


    // all counters should be empty before firing request
    withClue("before request:") {
      for { counter <- allCounters }
        assertCounter(counter).isEmpty
    }

    val req = Request("http", Method.Get, "hihost", "/", Stream.empty())
    var rsp = await(service(req))

    withClue("after first response") {
      for { counter <- successCounters :+ requestCounter }
        assertCounter(counter) is 1
    }

    rsp = await(service(req))

    withClue("after second response") {
      for { counter <- successCounters :+ requestCounter }
        assertCounter(counter) is 2
    }
  }

  test("does not increment failure counters after successes") {
    val (_stats, service) = setup { _ =>
      val stream = Stream.const("aaaaaaaaa")
      Future.value(Response(Status.Ok, stream))
    }

    implicit val stats = _stats

    val req = Request("http", Method.Get, "hihost", "/", Stream.empty())
    var rsp = await(service(req))
    await(rsp.stream.readToEnd)

    withClue("after first response") {
      for { counter <- failureCounters }
        assertCounter(counter).isEmpty
    }

    rsp = await(service(req))
    await(rsp.stream.readToEnd)

    withClue("after second response") {
      for { counter <- failureCounters }
        assertCounter(counter).isEmpty
    }
  }

  object Thrown extends Throwable

  test("increments correct failure counters after failure") {
    val (_stats, service) = setup { _ =>
      Future.exception(Thrown)
    }

    implicit val stats = _stats

    val req = Request("http", Method.Get, "hihost", "/", Stream.empty())
    val expectedCounters = Seq(
      requestCounter,
      Seq("failures"),
      Seq("request", "stream", "stream_success")
    )

    assertThrows[Throwable] { await(service(req)) }
    withClue("after first failure") {
      for { counter <- expectedCounters }
        assertCounter(counter) is 1
      // counters that should not be incremented on this failure
      for { counter <- failureCounters if !expectedCounters.contains(counter) }
       assertCounter(counter).isEmpty

    }

    assertThrows[Throwable] { await(service(req)) }
    withClue("after second failure") {
      for { counter <- expectedCounters }
        withClue(s"stat: $counter") { assertCounter(counter) is 2 }

      // counters that should not be incremented on this failure
      for { counter <- failureCounters if !expectedCounters.contains(counter) }
        withClue(s"stat: $counter") { assertCounter(counter).isEmpty }
    }
  }

  test("stats are defined after success") {
    val (_stats, service) = setup { _ =>
      Future.value(Response(Status.Ok, Stream.const("aaaaaaaaa")))
    }

    implicit val stats = _stats

    withClue("before request:") {
      // stats undefined before request
      for { stat <- allStats }
        withClue(s"stat: $stat") { assertStat(stat).isEmpty }
    }

    val req = Request("http", Method.Get, "hihost", "/", Stream.empty())
    val rsp = await(service(req))
    await(rsp.stream.readToEnd)

    withClue("after success:") {
      for { stat <- allStats } {
          assertStat(stat).isDefined
          assertStat(stat) hasLength 1
        }
    }
  }

  test("frame size stat for a single frame") {
    val data = "aaaaaaaa"
    val (_stats, service) = setup { _ =>
      Future.value(Response(Status.Ok, Stream.const(data)))
    }

    implicit val stats = _stats

    withClue("before request:") {
      // stats undefined before request
      assertStat(rspFrameSizeStat).isEmpty
    }

    val req = Request("http", Method.Get, "hihost", "/", Stream.empty())
    val rsp = await(service(req))
    await(rsp.stream.readToEnd)

    withClue("after success:") {
      assertStat(reqFrameSizeStat) contains 0
      assertStat(reqFrameSizeStat) hasLength 1
      assertStat(rspFrameSizeStat) contains data.length
      assertStat(rspFrameSizeStat) hasLength 1

    }
  }

  test("frame size stat for an empty stream") {
    val (_stats, service) = setup { _ =>
      Future.value(Response(Status.Ok, Stream.empty()))
    }

    implicit val stats = _stats

    withClue("before request:") {
      // stats undefined before request
      assertStat(rspFrameSizeStat).isEmpty
    }

    val req = Request("http", Method.Get, "hihost", "/", Stream.empty())
    val rsp = await(service(req))
    withClue("after success:") {
      assertStat(reqFrameSizeStat).contains(0)
      assertStat(reqFrameSizeStat).hasLength(1)
      assertStat(rspFrameSizeStat).contains(0)
      assertStat(rspFrameSizeStat).hasLength(1)

    }
  }

  test("frame size stat for a stream with multiple frames") {
    val data1 = "aaaaaaaa"
    val data2 = "bbbbbbbbbb"
    val streamBytes: Float = data1.length + data2.length
    val (_stats, service) = setup { _ =>
      val q = new AsyncQueue[Frame]()
      val stream = Stream(q)
      q.offer(Frame.Data(data1, eos = false))
      q.offer(Frame.Data(data2, eos = true))
      Future.value(Response(Status.Ok, stream))
    }

    implicit val stats = _stats

    withClue("before request:") {
      // stats undefined before request
      assertStat(rspFrameSizeStat).isEmpty
    }

    val req = Request("http", Method.Get, "hihost", "/", Stream.empty())
    var rsp = await(service(req))
    await(rsp.stream.readToEnd)

    withClue("after success:") {
      assertStat(reqFrameSizeStat).contains(0)
      assertStat(reqFrameSizeStat).hasLength(1)
      assertStat(rspFrameSizeStat).contains(streamBytes)
      assertStat(rspFrameSizeStat).hasLength(1)

    }
    rsp = await(service(req))
    await(rsp.stream.readToEnd)

    withClue("after second success:") {
      assertStat(reqFrameSizeStat).contains(0)
      assertStat(reqFrameSizeStat).hasLength(2)
      assertStat(rspFrameSizeStat).forall(_ == streamBytes)
      assertStat(rspFrameSizeStat).hasLength(2)

    }
  }

  test("stats are defined after failure") {
    val (_stats, service) = setup { _ =>
      Future.exception(Thrown)
    }

    implicit val stats = _stats

    withClue("before request:") {
      // stats undefined before request
      for { stat <- allStats }
        assertStat(stat).isEmpty
    }

    val req = Request("http", Method.Get, "hihost", "/", Stream.empty())
    assertThrows[Throwable] { await(service(req)) }

    withClue("after failure:") {
      for { stat <- reqStats }
        assertStat(stat).isDefined
    }
  }
}
