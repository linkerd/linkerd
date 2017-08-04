package io.buoyant.linkerd.protocol.h2

import java.net.InetSocketAddress
import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle._
import com.twitter.finagle.buoyant.H2
import com.twitter.finagle.buoyant.h2.Frame.Trailers
import com.twitter.finagle.buoyant.h2.{Frame, LinkerdHeaders, Method, Request, Response, Status, Stream, StreamError}
import com.twitter.finagle.stats.{InMemoryStatsReceiver, NullStatsReceiver}
import com.twitter.finagle.tracing.NullTracer
import com.twitter.util.{Future, Return, Var}
import io.buoyant.linkerd.Linker
import io.buoyant.test.{Awaits, FunSuite}
import io.buoyant.test.h2.StreamTestUtils._
import org.scalatest.Assertion

class StreamClassificationEndToEndTest extends FunSuite with Awaits {

  case class Downstream(name: String, service: Service[Request, Response]) {
    val stack = H2.server.stack.remove(LinkerdHeaders.Ctx.serverModule.role)
    val server = H2.server.withStack(stack)
      .configured(param.Label(name))
      .configured(param.Tracer(NullTracer))
      .serve(":*", service)
    val address = server.boundAddress.asInstanceOf[InetSocketAddress]
    val port = address.getPort
  }

  def upstream(server: ListeningServer) = {
    val address = Address(server.boundAddress.asInstanceOf[InetSocketAddress])
    val name = Name.Bound(Var.value(Addr.Bound(address)), address)
    val stack = H2.client.stack.remove(LinkerdHeaders.Ctx.clientModule.role)
    H2.client.withStack(stack)
      .configured(param.Stats(NullStatsReceiver))
      .configured(param.Tracer(NullTracer))
      .newClient(name, "upstream").toService
  }


  private[this] val successReqCounters = Seq(
    Seq("request", "stream", "stream_success"),
    Seq("requests")
  )

  private[this] val successRspCounters = Seq(
    Seq("response", "stream", "stream_success"),
    Seq("stream", "stream_success"),
    Seq("success")
  )

  private[this] val failureReqCounters = Seq(
    //    Seq("request", "stream", "stream_failures"),/**/
    Seq("requests")
  )

  private[this] val failureRspCounters = Seq(
    Seq("response", "stream", "stream_success"),
    Seq("failures")
  )

  def requestSuccessCounters(port: Int, svc: String): Seq[Seq[String]] =
    for {counter <- successReqCounters} yield {
      Seq("rt", "h2", "server", "127.0.0.1/0") ++ counter
    }

  def responseSuccessCounters(port: Int, svc: String): Seq[Seq[String]] =
    requestSuccessCounters(port, svc) ++
      (for {counter <- successRspCounters} yield {
        Seq("rt", "h2", "server", "127.0.0.1/0") ++ counter
      }) :+
      Seq("rt", "h2", "client", s"$$/inet/127.1/$port", "service", s"svc/$svc", "success")


  def requestFailureCounters(port: Int, svc: String): Seq[Seq[String]] =
    for {counter <- failureReqCounters} yield {
      Seq("rt", "h2", "server", "127.0.0.1/0") ++ counter
    }

  def responseFailureCounters(port: Int, svc: String): Seq[Seq[String]] =
    (for {counter <- failureRspCounters} yield {
      Seq("rt", "h2", "server", "127.0.0.1/0") ++ counter
    }) :+
      Seq("rt", "h2", "client", s"$$/inet/127.1/$port", "service", s"svc/$svc", "failures")

  test("success") {
    val downstream = Downstream("ds", Service.mk { req =>
      Future.value(Response(Status.Ok, Stream.const("aaaaaaa")))
    })

    val config =
      s"""|routers:
          |- protocol: h2
          |  experimental: true
          |  dtab: /svc/* => /$$/inet/127.1/${downstream.port}
          |  service:
          |    responseClassifier:
          |      kind: io.l5d.h2.retryableRead5XX
          |  servers:
          |  - port: 0
          |""".stripMargin

    val stats = new InMemoryStatsReceiver
    val linker = Linker.load(config).configured(param.Stats(stats))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()
    val client = upstream(server)

    val req = Request("http", Method.Get, "foo", "/", Stream.empty())
    val rsp = await(client(req))

    withClue("after request, before response:") {
      for {counter <- requestSuccessCounters(downstream.port, "foo")} {
        assert(stats.counters(counter) == 1)
      }
    }

    await(rsp.stream.readToEnd)

    withClue("after response:") {
      for {counter <- responseSuccessCounters(downstream.port, "foo")} {
        assert(stats.counters(counter) == 1)
      }
    }

  }

  test("non-retryable failure") {
    val downstream = Downstream("ds", Service.mk { req =>
      Future.value(Response(Status.InternalServerError, Stream.empty()))
    })

    val config =
      s"""|routers:
          |- protocol: h2
          |  experimental: true
          |  dtab: /svc/* => /$$/inet/127.1/${downstream.port}
          |  service:
          |    responseClassifier:
          |      kind: io.l5d.h2.retryableRead5XX
          |  servers:
          |  - port: 0
          |""".stripMargin

    val stats = new InMemoryStatsReceiver
    val linker = Linker.load(config).configured(param.Stats(stats))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()
    val client = upstream(server)

    val req = Request("http", Method.Post, "foo", "/", Stream.empty())
    val rsp = await(client(req))

    withClue("after request, before response:") {
      for {counter <- requestSuccessCounters(downstream.port, "foo")} {
        assert(stats.counters(counter) == 1)
      }
    }

    await(rsp.stream.onEnd)

    withClue("after response:") {
      for {counter <- responseFailureCounters(downstream.port, "foo")} {
        assert(stats.counters(counter) == 1)
      }
    }
  }

  ignore("retryable failure") {
    // disabled b/c retries isn't done yet
    @volatile var i = 0
    val downstream = Downstream("ds", Service.mk { req =>
      if (i == 0) {
        i += 1
        val rsp = Response(Status.InternalServerError, Stream.empty)
        Future.value(rsp)
      } else {
        i += 1
        Future.value(Response(Status.Ok, Stream.empty))
      }
    })
    val config =
      s"""|routers:
          |- protocol: h2
          |  experimental: true
          |  dtab: /svc/* => /$$/inet/127.1/${downstream.port}
          |  service:
          |    responseClassifier:
          |      kind: io.l5d.h2.retryableRead5XX
          |  servers:
          |  - port: 0
          |""".stripMargin

    val stats = new InMemoryStatsReceiver
    val linker = Linker.load(config).configured(param.Stats(stats))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()
    val client = upstream(server)

    val req = Request("http", Method.Post, "foo", "/", Stream.empty())
    val rsp = await(client(req))
    await(rsp.stream.readToEnd)

    assert(stats.counters(Seq("rt", "h2", "server", "127.0.0.1/0", "success")) == 1)
    assert(stats.counters.get(Seq("rt", "h2", "server", "127.0.0.1/0", "failures")) == None)
    assert(stats.counters(Seq("rt", "h2", "service", "svc/foo", "success")) == 1)
    assert(stats.counters.get(Seq("rt", "h2", "service", "svc/foo", "failures")) == None)
    assert(stats
      .counters(Seq("rt", "h2", "client", s"$$/inet/127.1/${downstream.port}", "service", "svc/foo", "success")) == 1)
    assert(stats
      .counters(Seq("rt", "h2", "client", s"$$/inet/127.1/${downstream.port}", "service", "svc/foo", "failures")) == 1)
  }

  ignore("per service classification") {
    // this uses retries, which don't work yet
    @volatile var i = 0
    val downstream = Downstream("ds", Service.mk { req =>
      if (i % 2 == 0) {
        i += 1
        val rsp = Response(Status.InternalServerError, Stream.empty)
        Future.value(rsp)
      } else {
        i += 1
        Future.value(Response(Status.Ok, Stream.const("aaaaaa")))
      }
    })
    val config =
      s"""|routers:
          |- protocol: h2
          |  experimental: true
          |  dtab: /svc/* => /$$/inet/127.1/${downstream.port}
          |  service:
          |    kind: io.l5d.static
          |    configs:
          |    - prefix: /svc/a
          |      responseClassifier:
          |        kind: io.l5d.h2.retryableRead5XX
          |    - prefix: /svc/b
          |      responseClassifier:
          |        kind: io.l5d.h2.nonRetryable5XX
          |  servers:
          |  - port: 0
          |""".stripMargin

    val stats = new InMemoryStatsReceiver
    val linker = Linker.load(config).configured(param.Stats(stats))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()
    val client = upstream(server)

    var req = Request("http", Method.Post, "a", "/", Stream.empty())
    var rsp = await(client(req))
    await(rsp.stream.readToEnd)

    withClue("after first response: ") {
      assert(stats.counters.get(Seq("rt", "h2", "server", "127.0.0.1/0", "success")) == Some(1),
        s", did get ${stats.counters}")
      assert(stats.counters.get(Seq("rt", "h2", "server", "127.0.0.1/0", "failures")) == None)
      assert(stats.counters(Seq("rt", "h2", "service", "svc/a", "success")) == 1)
      assert(stats.counters.get(Seq("rt", "h2", "service", "svc/a", "failures")) == None)
      assert(stats
        .counters(Seq("rt", "h2", "client", s"$$/inet/127.1/${downstream.port}", "service", "svc/a", "success")) == 1)
      assert(stats
        .counters(Seq("rt", "h2", "client", s"$$/inet/127.1/${downstream.port}", "service", "svc/a", "failures")) == 1)
    }

    // Request to "b" is non-retryable
    // fails and doesn't retry

    req = Request("http", Method.Post, "b", "/", Stream.empty())
    rsp = await(client(req))
    await(rsp.stream.readToEnd)

    withClue("after second response: ") {
      assert(stats.counters(Seq("rt", "h2", "server", "127.0.0.1/0", "success")) == 1)
      assert(stats.counters(Seq("rt", "h2", "server", "127.0.0.1/0", "failures")) == 1)
      assert(stats.counters.get(Seq("rt", "h2", "service", "svc/b", "success")) == None)
      assert(stats.counters(Seq("rt", "h2", "service", "svc/b", "failures")) == 1)
      assert(stats.counters
        .get(Seq("rt", "h2", "client", s"$$/inet/127.1/${downstream.port}", "service", "svc/b", "success")) == None)
      assert(stats
        .counters(Seq("rt", "h2", "client", s"$$/inet/127.1/${downstream.port}", "service", "svc/b", "failures")) == 1)
    }
  }

  def mkTestClassifier(downstream: Downstream)
    (classifier: String)
    (expect: InMemoryStatsReceiver => Assertion): Assertion = {
    val config =
      s"""|routers:
          |- protocol: h2
          |  experimental: true
          |  dtab: /svc/* => /$$/inet/127.1/${downstream.port}
          |  service:
          |    responseClassifier:
          |      kind: $classifier
          |  servers:
          |  - port: 0
          |""".stripMargin

    val stats = new InMemoryStatsReceiver
    val linker = Linker.load(config).configured(param.Stats(stats))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()
    val client = upstream(server)

    val req = Request("http", Method.Post, "foo", "/", Stream.empty)
    val rsp = await(client(req))
    await(rsp.stream.readToEnd)
    withClue(s"classifier: $classifier") {
      expect(stats)
    }
  }

  test("client stats use service response classifier") {
    val downstream = Downstream("ds", Service.mk { req =>
      val rsp = Response(Status.InternalServerError, Stream.empty)
      Future.value(rsp)
    })
    val testClassifier = mkTestClassifier(downstream) _

    testClassifier("io.l5d.h2.allSuccessful") { stats =>
      assert(stats.counters.get(Seq("rt", "h2", "service", "svc/foo", "success")) == Some(1))
      assert(stats.counters.get(Seq("rt", "h2", "service", "svc/foo", "failures")) == None)
      assert(stats.counters.get(Seq("rt", "h2", "client", s"$$/inet/127.1/${downstream.port}", "service", "svc/foo", "success")) == Some(1))
      assert(stats.counters.get(Seq("rt", "h2", "client", s"$$/inet/127.1/${downstream.port}", "service", "svc/foo", "failures")) == None)
    }

    testClassifier("io.l5d.h2.nonRetryable5XX") { stats =>
      for {counter <- responseFailureCounters(downstream.port, "foo")} {
        assert(stats.counters(counter) == 1)
      }
      succeed
    }

  }

  test("server stats use service response classifier") {
    val downstream = Downstream("ds", Service.mk { req =>
      val rsp = Response(Status.InternalServerError, Stream.empty)
      Future.value(rsp)
    })
    val testClassifier = mkTestClassifier(downstream) _

    testClassifier("io.l5d.h2.allSuccessful") { stats =>
      assert(stats.counters.get(Seq("rt", "h2", "server", "127.0.0.1/0", "success")).contains(1), s"did get ${stats.counters}")
      assert(stats.counters.get(Seq("rt", "h2", "server", "127.0.0.1/0", "failures")).isEmpty)
      succeed
    }

    testClassifier("io.l5d.h2.nonRetryable5XX") { stats =>
      assert(stats.counters.get(Seq("rt", "h2", "server", "127.0.0.1/0", "success")).isEmpty)
      assert(stats.counters.get(Seq("rt", "h2", "server", "127.0.0.1/0", "failures")).contains(1))
      succeed
    }
  }

  test("classifier filter sets l5d-success-class headers") {
    val downstream = Downstream("ds", Service.mk { req =>
      val rsp = Response(Status.Ok, Stream.empty)
      Future.value(rsp)
    })
    val config =
      s"""|routers:
          |- protocol: h2
          |  experimental: true
          |  dtab: /svc/* => /$$/inet/127.1/${downstream.port}
          |  service:
          |    responseClassifier:
          |      kind: io.l5d.h2.allSuccessful
          |  servers:
          |  - port: 0
          |""".stripMargin

    val stats = new InMemoryStatsReceiver
    val linker = Linker.load(config).configured(param.Stats(stats))
    val router = linker.routers .head.initialize()
    val server = router.servers.head.serve()
    val client = upstream(server)
    val req = Request("http", Method.Get, "foo", "/", Stream.empty)
    val rsp = await(client(req))
    await(rsp.stream.readToEnd)

    withClue("after request") {
      assert(rsp.headers.contains("l5d-success-class"), s", did get headers: ${rsp.headers.toSeq}")
    }
  }

  test("classifier filter sets l5d-success-class headers in stream trailers") {
    val q = new AsyncQueue[Frame]()
    val downstream = Downstream("ds", Service.mk { req =>
      val rsp = Response(Status.Ok, Stream(q))
      q.offer(Frame.Data("aaaaaaaa", eos = false))
      q.offer(Frame.Data("bbbbb", eos = false))
      q.offer(Frame.Trailers())
      Future.value(rsp)
    })
    val config =
      s"""|routers:
          |- protocol: h2
          |  experimental: true
          |  dtab: /svc/* => /$$/inet/127.1/${downstream.port}
          |  service:
          |    responseClassifier:
          |      kind: io.l5d.h2.allSuccessful
          |  servers:
          |  - port: 0
          |""".stripMargin

    val stats = new InMemoryStatsReceiver
    val linker = Linker.load(config).configured(param.Stats(stats))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()
    val client = upstream(server)
    val req = Request("http", Method.Get, "foo", "/", Stream.empty)
    val rsp = await(client(req))
    assert(!rsp.headers.contains( "l5d-success-class"))

    var trailersFound = false
    val stream = rsp.stream.onFrame {
      case Return(frame: Trailers) =>
        assert(frame.get("l5d-success-class") == Some("1.0"),
          s", did get headers: ${rsp.headers}")
        trailersFound = true
      case _ =>
    }
    await(stream.readToEnd)

    assert(trailersFound,", meaning we never received stream trailers!")
  }

}
