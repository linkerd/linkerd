package io.buoyant.router.h2

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.{Service, ServiceFactory, Stack, param}
import com.twitter.finagle.buoyant.h2.{Frame, Method, Request, Response, Status, Stream}
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.util.Future
import io.buoyant.test.{Awaits, FunSuite}
import io.buoyant.test.h2.StreamTestUtils._

class StreamTestStatsFilterTest extends FunSuite with Awaits {
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

  private[this] def setup(response: Request => Future[Response]) = {

    val stats = new InMemoryStatsReceiver()
    val svc = Service.mk[Request, Response] { response(_) }
    val stk = StreamStatsFilter.module.toStack(
      Stack.Leaf(StreamStatsFilter.role, ServiceFactory.const(svc))
    )
    val service = await(stk.make(Stack.Params.empty + param.Stats(stats))())

    (stats, service)
  }

  test("increments success counters on success") {

    val (stats, service) = setup { _ =>
      Future.value(Response(Status.Ok, Stream.const("aaaaaaaaaaaa")))
    }

    // all counters should be empty before firing request
    withClue("before request:") {
      for { counter <- allCounters }
        withClue(s"stat: $counter") { assert(!stats.counters.isDefinedAt(counter)) }
    }

    val req = Request("http", Method.Get, "hihost", "/", Stream.empty())
    var rsp = await(service(req))
    await(rsp.stream.readToEnd)

    withClue("after first response") {
      for { counter <- successCounters :+ requestCounter }
        withClue(s"stat: $counter") { assert(stats.counters(counter) == 1) }
    }

    rsp = await(service(req))
    await(rsp.stream.readToEnd)

    withClue("after second response") {
      for { counter <- successCounters :+ requestCounter }
        withClue(s"stat: $counter") { assert(stats.counters(counter) == 2) }
    }
  }

  test("increments success counters on success with empty stream") {

    val (stats, service) = setup { _ =>
      Future.value(Response(Status.Ok, Stream.empty()))
    }

    // all counters should be empty before firing request
    withClue("before request:") {
      for { counter <- allCounters }
        withClue(s"stat: $counter") { assert(!stats.counters.isDefinedAt(counter)) }
    }

    val req = Request("http", Method.Get, "hihost", "/", Stream.empty())
    var rsp = await(service(req))

    withClue("after first response") {
      for { counter <- successCounters :+ requestCounter }
        withClue(s"stat: $counter") { assert(stats.counters(counter) == 1) }
    }

    rsp = await(service(req))

    withClue("after second response") {
      for { counter <- successCounters :+ requestCounter }
        withClue(s"stat: $counter") { assert(stats.counters(counter) == 2) }
    }
  }

  test("does not increment failure counters after successes") {
    val (stats, service) = setup { _ =>
      val stream = Stream.const("aaaaaaaaa")
      Future.value(Response(Status.Ok, stream))
    }

    val req = Request("http", Method.Get, "hihost", "/", Stream.empty())
    var rsp = await(service(req))
    await(rsp.stream.readToEnd)

    withClue("after first response") {
      for { counter <- failureCounters }
        withClue(s"stat: $counter") { assert(!stats.counters.isDefinedAt(counter)) }
    }

    rsp = await(service(req))
    await(rsp.stream.readToEnd)

    withClue("after second response") {
      for { counter <- failureCounters }
        withClue(s"stat: $counter") { assert(!stats.counters.isDefinedAt(counter)) }
    }
  }

  object Thrown extends Throwable

  test("increments correct failure counters after failure") {
    val (stats, service) = setup { _ =>
      Future.exception(Thrown)
    }
    val req = Request("http", Method.Get, "hihost", "/", Stream.empty())
    val expectedCounters = Seq(
      requestCounter,
      Seq("failures"),
      Seq("request", "stream", "stream_success")
    )

    assertThrows[Throwable] { await(service(req)) }
    withClue("after first failure") {
      for { counter <- expectedCounters }
        assert(stats.counters(counter) == 1)
      // counters that should not be incremented on this failure
      for { counter <- failureCounters if !expectedCounters.contains(counter) }
        withClue(s"stat: $counter") { assert(!stats.counters.isDefinedAt(counter)) }

    }

    assertThrows[Throwable] { await(service(req)) }
    withClue("after second failure") {
      for { counter <- expectedCounters }
        withClue(s"stat: $counter") { assert(stats.counters(counter) == 2) }

      // counters that should not be incremented on this failure
      for { counter <- failureCounters if !expectedCounters.contains(counter) }
        withClue(s"stat: $counter") { assert(!stats.counters.isDefinedAt(counter)) }
    }
  }

  test("stats are defined after success") {
    val (stats, service) = setup { _ =>
      Future.value(Response(Status.Ok, Stream.const("aaaaaaaaa")))
    }
    withClue("before request:") {
      // stats undefined before request
      for { stat <- allStats }
        withClue(s"stat: $stat") { assert(!stats.stats.isDefinedAt(stat)) }
    }

    val req = Request("http", Method.Get, "hihost", "/", Stream.empty())
    val rsp = await(service(req))
    await(rsp.stream.readToEnd)

    withClue("after success:") {
      for { stat <- allStats }
        withClue(s"stat: $stat") {
          assert(stats.stats.isDefinedAt(stat))
          assert(stats.stats(stat).length == 1)
        }
    }
  }

  test("frame size stat for a single frame") {
    val data = "aaaaaaaa"
    val (stats, service) = setup { _ =>
      Future.value(Response(Status.Ok, Stream.const(data)))
    }
    withClue("before request:") {
      // stats undefined before request
      assert(!stats.stats.isDefinedAt(rspFrameSizeStat))
    }

    val req = Request("http", Method.Get, "hihost", "/", Stream.empty())
    val rsp = await(service(req))
    await(rsp.stream.readToEnd)

    withClue("after success:") {
      assert(stats.stats(reqFrameSizeStat).contains(0))
      assert(stats.stats(reqFrameSizeStat).length == 1)
      assert(stats.stats(rspFrameSizeStat).contains(data.length))
      assert(stats.stats(rspFrameSizeStat).length == 1)

    }
  }

  test("frame size stat for an empty stream") {
    val (stats, service) = setup { _ =>
      Future.value(Response(Status.Ok, Stream.empty()))
    }
    withClue("before request:") {
      // stats undefined before request
      assert(!stats.stats.isDefinedAt(rspFrameSizeStat))
    }

    val req = Request("http", Method.Get, "hihost", "/", Stream.empty())
    val rsp = await(service(req))
    withClue("after success:") {
      assert(stats.stats(reqFrameSizeStat).contains(0))
      assert(stats.stats(reqFrameSizeStat).length == 1)
      assert(stats.stats(rspFrameSizeStat).contains(0))
      assert(stats.stats(rspFrameSizeStat).length == 1)

    }
  }

  test("frame size stat for a stream with multiple frames") {
    val data1 = "aaaaaaaa"
    val data2 = "bbbbbbbbbb"
    val streamBytes: Float = data1.length + data2.length
    val (stats, service) = setup { _ =>
      val q = new AsyncQueue[Frame]()
      val stream = Stream(q)
      q.offer(Frame.Data(data1, eos = false))
      q.offer(Frame.Data(data2, eos = true))
      Future.value(Response(Status.Ok, stream))
    }

    withClue("before request:") {
      // stats undefined before request
      assert(!stats.stats.isDefinedAt(rspFrameSizeStat))
    }

    val req = Request("http", Method.Get, "hihost", "/", Stream.empty())
    var rsp = await(service(req))
    await(rsp.stream.readToEnd)

    withClue("after success:") {
      assert(stats.stats(reqFrameSizeStat).contains(0))
      assert(stats.stats(reqFrameSizeStat).length == 1)
      assert(stats.stats(rspFrameSizeStat).contains(streamBytes))
      assert(stats.stats(rspFrameSizeStat).length == 1)

    }
    rsp = await(service(req))
    await(rsp.stream.readToEnd)

    withClue("after second success:") {
      assert(stats.stats(reqFrameSizeStat).contains(0))
      assert(stats.stats(reqFrameSizeStat).length == 2)
      assert(stats.stats(rspFrameSizeStat).forall(_ == streamBytes))
      assert(stats.stats(rspFrameSizeStat).length == 2)

    }
  }

  test("stats are defined after failure") {
    val (stats, service) = setup { _ =>
      Future.exception(Thrown)
    }
    withClue("before request:") {
      // stats undefined before request
      for { stat <- allStats }
        withClue(s"stat: $stat") { assert(!stats.stats.isDefinedAt(stat)) }
    }

    val req = Request("http", Method.Get, "hihost", "/", Stream.empty())
    assertThrows[Throwable] { await(service(req)) }

    withClue("after failure:") {
      for { stat <- reqStats }
        withClue(s"stat: $stat") { assert(stats.stats.isDefinedAt(stat)) }
    }
  }
}
