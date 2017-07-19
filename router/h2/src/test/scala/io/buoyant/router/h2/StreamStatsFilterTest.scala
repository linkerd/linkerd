package io.buoyant.router.h2

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.buoyant.h2.Frame.Trailers
import com.twitter.finagle.{Service, ServiceFactory, Stack, param}
import com.twitter.finagle.buoyant.h2.{Frame, Method, Request, Response, Status, Stream}
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.io.Buf
import com.twitter.util.{Future, Return}
import io.buoyant.test.{Awaits, FunSuite}
import scala.annotation.tailrec

class StreamStatsFilterTest extends FunSuite with Awaits {
  val successCounters = Seq(
    Seq("response", "stream", "stream_successes"),
    Seq("request", "stream", "stream_successes"),
    Seq("stream", "stream_successes"),
    Seq("successes")
  )
  val failureCounters = Seq(
    Seq("response", "stream", "stream_failures"),
    Seq("request", "stream", "stream_failures"),
    Seq("stream", "stream_failures"),
    Seq("failures")
  )
  val requestCounter = Seq("requests")
  val allCounters = successCounters ++ failureCounters :+ requestCounter
  val latencyStats = Seq(
    Seq("response", "stream", "stream_duration_ms"),
    Seq("request", "stream", "stream_duration_ms"),
    Seq("stream", "total_latency_ms"),
    Seq("request_latency_ms")
  )
  val frameSizeStat = Seq("stream", "data_frame", "bytes")

  private[this] def setup(response: Request => Future[Response]) = {

    val stats = new InMemoryStatsReceiver()
    val svc = Service.mk[Request, Response] { response(_) }
    val stk = StreamStatsFilter.module.toStack(
      Stack.Leaf(StreamStatsFilter.role, ServiceFactory.const(svc))
    )
    val service = await(stk.make(Stack.Params.empty + param.Stats(stats))())

    (stats, service)
  }

  private[this] def readAll(stream: Stream): Future[Unit] =
    stream.read().flatMap { frame =>
      val end = frame.isEnd
      frame.release().flatMap { _ =>
        if (end) Future.Done else readAll(stream)
      }
    }

  test("increments success counters on success") {

    val (stats, service) = setup { _ =>
      Future.value(Response(Status.Ok, Stream.const("aaaaaaaaaaaa")))
    }

    // all counters should be empty before firing request
    withClue("before request") {
      for { counter <- allCounters }
        withClue(s"stat: $counter") { assert(!stats.counters.isDefinedAt(counter)) }
    }

    val req = Request("http", Method.Get, "hihost", "/", Stream.empty())
    var rsp = await(service(req))
    await(readAll(rsp.stream))

    withClue("after first response") {
      for { counter <- successCounters :+ requestCounter }
        withClue(s"stat: $counter") { assert(stats.counters(counter) == 1) }
    }

    rsp = await(service(req))
    await(readAll(rsp.stream))

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
    await(readAll(rsp.stream))

    withClue("after first response") {
      for { counter <- failureCounters }
        withClue(s"stat: $counter") { assert(!stats.counters.isDefinedAt(counter)) }
    }

    rsp = await(service(req))
    await(readAll(rsp.stream))

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
      Seq("request", "stream", "stream_successes")
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
    withClue("before request") {
      // stats undefined before request
      for { stat <- latencyStats }
        withClue(s"stat: $stat") { assert(!stats.stats.isDefinedAt(stat)) }
    }

    val req = Request("http", Method.Get, "hihost", "/", Stream.empty())
    val rsp = await(service(req))
    await(readAll(rsp.stream))

    withClue("after success") {
      for { stat <- latencyStats }
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
    withClue("before request") {
      // stats undefined before request
      assert(!stats.stats.isDefinedAt(Seq("stream", "data_frame", "bytes")))
    }

    val req = Request("http", Method.Get, "hihost", "/", Stream.empty())
    val rsp = await(service(req))
    await(readAll(rsp.stream))

    withClue("after success") {
      val length = data.length.asInstanceOf[Float]
      assert(stats.stats(Seq("stream", "data_frame", "bytes")).contains(length))
      assert(stats.stats(Seq("stream", "data_frame", "bytes")).length == 1)

    }
  }

  test("frame size stat for a stream with multiple frames") {
    val data1 = "aaaaaaaa"
    val data2 = "bbbbbbbbbb"
    val (stats, service) = setup { _ =>
      val q = new AsyncQueue[Frame]()
      val stream = Stream(q)
      q.offer(Frame.Data(data1, eos = false))
      q.offer(Frame.Data(data2, eos = true))
      Future.value(Response(Status.Ok, stream))
    }

    withClue("before request") {
      // stats undefined before request
      assert(!stats.stats.isDefinedAt(Seq("stream", "data_frame", "bytes")))
    }

    val req = Request("http", Method.Get, "hihost", "/", Stream.empty())
    var rsp = await(service(req))
    await(readAll(rsp.stream))

    withClue("after success") {
      assert(stats.stats(frameSizeStat)
        .contains(data1.length.asInstanceOf[Float]))
      assert(stats.stats(frameSizeStat)
        .contains(data2.length.asInstanceOf[Float]))
      assert(stats.stats(frameSizeStat).length == 2)

    }
    rsp = await(service(req))
    await(readAll(rsp.stream))

    withClue("after second success") {
      assert(stats.stats(frameSizeStat).length == 4)
    }
  }



  test("stats are defined after failure") {
    val (stats, service) = setup { _ =>
      Future.exception(Thrown)
    }
    withClue("before request") {
      // stats undefined before request
      for { stat <- latencyStats }
        withClue(s"stat: $stat") { assert(!stats.stats.isDefinedAt(stat)) }
    }

    val req = Request("http", Method.Get, "hihost", "/", Stream.empty())
    assertThrows[Throwable] { await(service(req)) }
    withClue("after failure") {
      for { stat <- latencyStats.filter(_ != Seq("response", "stream", "stream_duration_ms")) }
        withClue(s"stat: $stat") { assert(stats.stats.isDefinedAt(stat)) }
    }
  }
}
