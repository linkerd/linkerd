package io.buoyant.linkerd.protocol.h2

import java.net.InetSocketAddress
import com.twitter.finagle._
import com.twitter.finagle.buoyant.H2
import com.twitter.finagle.buoyant.h2.{LinkerdHeaders, Method, Request, Response, Status, Stream}
import com.twitter.finagle.stats.{InMemoryStatsReceiver, NullStatsReceiver}
import com.twitter.finagle.tracing.NullTracer
import com.twitter.util.{Future, Var}
import io.buoyant.linkerd.Linker
import io.buoyant.test.{Awaits, FunSuite, StatsAssertions}
import io.buoyant.test.h2.StreamTestUtils._

class StreamClassificationEndToEndTest
  extends FunSuite
    with Awaits
    with StatsAssertions {

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
  
  val serverNs = Seq("rt", "h2", "server", "127.0.0.1/0")

  def requestSuccessCounters(port: Int, svc: String): Seq[Seq[String]] =
    for { counter <- successReqCounters } yield { serverNs ++ counter}

  def responseSuccessCounters(port: Int, svc: String): Seq[Seq[String]] =
    requestSuccessCounters(port, svc) ++
    (for { counter <- successRspCounters } yield { serverNs ++ counter}) :+
      Seq("rt", "h2", "client", s"$$/inet/127.1/$port", "service", s"svc/$svc", "success")


  def requestFailureCounters(port: Int, svc: String): Seq[Seq[String]] =
    for { counter <- failureReqCounters } yield { serverNs ++ counter}

  def responseFailureCounters(port: Int, svc: String): Seq[Seq[String]] =
      (for { counter <- failureRspCounters } yield { serverNs ++ counter}) :+
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
      for { counter <- requestSuccessCounters(downstream.port, "foo") } {
        assertCounter(counter) is 1
      }
    }

    await(rsp.stream.readToEnd)

    withClue("after response:") {
      for { counter <- responseSuccessCounters(downstream.port, "foo") } {
        assertCounter(counter) is 1
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
      for { counter <- requestSuccessCounters(downstream.port, "foo") } {
        assertCounter(counter) is 1
      }
    }

    await(rsp.stream.onEnd)

    withClue("after response:") {
      for { counter <- responseFailureCounters(downstream.port, "foo") } {
        assertCounter(counter) is 1
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

    assertCounter(serverNs :+ "success") is 1
    assertCounter(serverNs :+ "failures").isEmpty
    assertCounter(Seq("rt", "h2", "service", "svc/foo", "success")) is 1
    assertCounter(Seq("rt", "h2", "service", "svc/foo", "failures")).isEmpty
    assertCounter(Seq("rt", "h2", "client", s"$$/inet/127.1/${downstream.port}", "service", "svc/foo", "success")) is 1
    assertCounter(Seq("rt", "h2", "client", s"$$/inet/127.1/${downstream.port}", "service", "svc/foo", "failures")) is 1
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

    val svcANs = Seq("rt", "h2", "service", "svc/a")
    val svcBNs = Seq("rt", "h2", "service", "svc/b")
    val clientNs = Seq("rt", "h2", "client", s"$$/inet/127.1/${downstream.port}")
    val clientSvcANs = clientNs ++ Seq("service", "svc/a")
    val clientSvcBNs = clientNs ++ Seq("service", "svc/b")

    withClue("after first response: ") {
      assertCounter(serverNs :+ "success") is 1
      assertCounter(serverNs :+ "failures") is 1
      assertCounter(svcANs :+ "success") is 1
      assertCounter(svcANs :+ "failures").isEmpty
      assertCounter(clientSvcANs :+ "success") is 1
      assertCounter(clientSvcANs :+ "failures") is 1
      assertCounter(clientSvcBNs :+ "success").isEmpty
      assertCounter(clientSvcBNs :+ "failures").isEmpty
    }

    // Request to "b" is non-retryable
    // fails and doesn't retry

    req = Request("http", Method.Post, "b", "/", Stream.empty())
    rsp = await(client(req))
    await(rsp.stream.readToEnd)

    withClue("after second response: ") {
      assertCounter(serverNs :+ "success") is 1
      assertCounter(serverNs :+ "failures") is 1
      assertCounter(svcBNs :+ "success") is 1
      assertCounter(svcBNs :+ "failures") is 1
      assertCounter(clientSvcBNs :+ "success").isEmpty
      assertCounter(clientSvcBNs :+ "failures") is 1
      assertCounter(clientSvcANs :+ "success") is 1
      assertCounter(clientSvcANs :+ "failures") is 1
    }
  }

  test("client stats use service response classifier") {
    val downstream = Downstream("ds", Service.mk { req =>
      val rsp = Response(Status.InternalServerError, Stream.empty())
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

    val req = Request("http", Method.Post, "foo", "/", Stream.empty())
    val rsp = await(client(req))
    await(rsp.stream.readToEnd)
    val svcNs = Seq("rt", "h2", "service", "svc/foo")
    val clientSvcNs = Seq("rt", "h2", "client", s"$$/inet/127.1/${downstream.port}", "service", "svc/foo")

    assertCounter(svcNs :+ "success") is 1
    assertCounter(svcNs :+ "failures").isEmpty
    assertCounter(clientSvcNs :+ "success") is 1
    assertCounter(clientSvcNs :+ "failures") is 1
  }
}
