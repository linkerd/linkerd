package io.buoyant.linkerd
package protocol

import com.twitter.finagle.buoyant.linkerd.Headers
import com.twitter.finagle.http.{Method, Response, Request}
import com.twitter.finagle.stats.{InMemoryStatsReceiver, NullStatsReceiver}
import com.twitter.finagle._
import com.twitter.util.{Future, Var}
import com.twitter.finagle.tracing.NullTracer
import io.buoyant.test.FunSuite
import java.net.InetSocketAddress

class ResponseClassificationEndToEndTest extends FunSuite {

  case class Downstream(name: String, service: Service[Request, Response]) {
    val stack = Http.server.stack.remove(Headers.Ctx.serverModule.role)
    val server = Http.server.withStack(stack)
      .configured(param.Label(name))
      .configured(param.Tracer(NullTracer))
      .serve(":*", service)
    val address = server.boundAddress.asInstanceOf[InetSocketAddress]
    val port = address.getPort
  }

  def upstream(server: ListeningServer) = {
    val address = Address(server.boundAddress.asInstanceOf[InetSocketAddress])
    val name = Name.Bound(Var.value(Addr.Bound(address)), address)
    val stack = Http.client.stack.remove(Headers.Ctx.clientModule.role)
    Http.client.withStack(stack)
      .configured(param.Stats(NullStatsReceiver))
      .configured(param.Tracer(NullTracer))
      .newClient(name, "upstream").toService
  }

  test("success") {
    val downstream = Downstream("ds", Service.mk { req => Future.value(Response()) })
    val config =
      s"""|routers:
          |- protocol: http
          |  dtab: /svc/* => /$$/inet/127.1/${downstream.port}
          |  service:
          |    responseClassifier:
          |      kind: io.l5d.http.retryableRead5XX
          |  servers:
          |  - port: 0
          |""".stripMargin

    val stats = new InMemoryStatsReceiver
    val linker = Linker.load(config).configured(param.Stats(stats))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()
    val client = upstream(server)

    val req = Request()
    req.host = "foo"
    await(client(req))

    assert(stats.counters(Seq("rt", "http", "client", s"$$/inet/127.1/${downstream.port}", "service", "svc/foo", "success")) == 1)
    assert(stats.counters(Seq("rt", "http", "service", "svc/foo", "success")) == 1)
    assert(stats.counters(Seq("rt", "http", "server", "127.0.0.1/0", "success")) == 1)
  }

  test("non-retryable failure") {

    val downstream = Downstream("ds", Service.mk { req =>
      val rsp = Response()
      rsp.statusCode = 500
      Future.value(rsp)
    })
    val config =
      s"""|routers:
          |- protocol: http
          |  dtab: /svc/* => /$$/inet/127.1/${downstream.port}
          |  service:
          |    responseClassifier:
          |      kind: io.l5d.http.retryableRead5XX
          |  servers:
          |  - port: 0
          |""".stripMargin

    val stats = new InMemoryStatsReceiver
    val linker = Linker.load(config).configured(param.Stats(stats))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()
    val client = upstream(server)

    val req = Request()
    req.method = Method.Post
    req.host = "foo"
    await(client(req))

    assert(stats.counters(Seq("rt", "http", "client", s"$$/inet/127.1/${downstream.port}", "service", "svc/foo", "failures")) == 1)
    assert(stats.counters(Seq("rt", "http", "service", "svc/foo", "failures")) == 1)
    assert(stats.counters(Seq("rt", "http", "server", "127.0.0.1/0", "failures")) == 1)
  }

  test("retryable failure") {
    @volatile var i = 0
    val downstream = Downstream("ds", Service.mk { req =>
      if (i == 0) {
        i += 1
        val rsp = Response()
        rsp.statusCode = 500
        Future.value(rsp)
      } else {
        i += 1
        Future.value(Response())
      }
    })
    val config =
      s"""|routers:
          |- protocol: http
          |  dtab: /svc/* => /$$/inet/127.1/${downstream.port}
          |  service:
          |    responseClassifier:
          |      kind: io.l5d.http.retryableRead5XX
          |  servers:
          |  - port: 0
          |""".stripMargin

    val stats = new InMemoryStatsReceiver
    val linker = Linker.load(config).configured(param.Stats(stats))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()
    val client = upstream(server)

    val req = Request()
    req.host = "foo"
    await(client(req))

    assert(stats.counters(Seq("rt", "http", "server", "127.0.0.1/0", "success")) == 1)
    assert(stats.counters.get(Seq("rt", "http", "server", "127.0.0.1/0", "failures")) == None)
    assert(stats.counters(Seq("rt", "http", "service", "svc/foo", "success")) == 1)
    assert(stats.counters.get(Seq("rt", "http", "service", "svc/foo", "failures")) == None)
    assert(stats.counters(Seq("rt", "http", "client", s"$$/inet/127.1/${downstream.port}", "service", "svc/foo", "success")) == 1)
    assert(stats.counters(Seq("rt", "http", "client", s"$$/inet/127.1/${downstream.port}", "service", "svc/foo", "failures")) == 1)
  }

  test("per service classification") {
    @volatile var i = 0
    val downstream = Downstream("ds", Service.mk { req =>
      if (i % 2 == 0) {
        i += 1
        val rsp = Response()
        rsp.statusCode = 500
        Future.value(rsp)
      } else {
        i += 1
        Future.value(Response())
      }
    })
    val config =
      s"""|routers:
          |- protocol: http
          |  dtab: /svc/* => /$$/inet/127.1/${downstream.port}
          |  service:
          |    kind: io.l5d.static
          |    configs:
          |    - prefix: /svc/a
          |      responseClassifier:
          |        kind: io.l5d.http.retryableRead5XX
          |    - prefix: /svc/b
          |      responseClassifier:
          |        kind: io.l5d.http.nonRetryable5XX
          |  servers:
          |  - port: 0
          |""".stripMargin

    val stats = new InMemoryStatsReceiver
    val linker = Linker.load(config).configured(param.Stats(stats))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()
    val client = upstream(server)

    val req = Request()
    // Request to "a" is retryable
    // succeeds after 1 failure
    req.host = "a"
    await(client(req))

    assert(stats.counters(Seq("rt", "http", "server", "127.0.0.1/0", "success")) == 1)
    assert(stats.counters.get(Seq("rt", "http", "server", "127.0.0.1/0", "failures")) == None)
    assert(stats.counters(Seq("rt", "http", "service", "svc/a", "success")) == 1)
    assert(stats.counters.get(Seq("rt", "http", "service", "svc/a", "failures")) == None)
    assert(stats.counters(Seq("rt", "http", "client", s"$$/inet/127.1/${downstream.port}", "service", "svc/a", "success")) == 1)
    assert(stats.counters(Seq("rt", "http", "client", s"$$/inet/127.1/${downstream.port}", "service", "svc/a", "failures")) == 1)

    // Request to "b" is non-retryable
    // fails and doesn't retry
    req.host = "b"
    await(client(req))

    assert(stats.counters(Seq("rt", "http", "server", "127.0.0.1/0", "success")) == 1)
    assert(stats.counters(Seq("rt", "http", "server", "127.0.0.1/0", "failures")) == 1)
    assert(stats.counters.get(Seq("rt", "http", "service", "svc/b", "success")) == None)
    assert(stats.counters(Seq("rt", "http", "service", "svc/b", "failures")) == 1)
    assert(stats.counters.get(Seq("rt", "http", "client", s"$$/inet/127.1/${downstream.port}", "service", "svc/b", "success")) == None)
    assert(stats.counters(Seq("rt", "http", "client", s"$$/inet/127.1/${downstream.port}", "service", "svc/b", "failures")) == 1)
  }

  test("client stats use service response classifier") {
    val downstream = Downstream("ds", Service.mk { req =>
      val rsp = Response()
      rsp.statusCode = 500
      Future.value(rsp)
    })
    val config =
      s"""|routers:
          |- protocol: http
          |  dtab: /svc/* => /$$/inet/127.1/${downstream.port}
          |  service:
          |    responseClassifier:
          |      kind: io.l5d.http.allSuccessful
          |  servers:
          |  - port: 0
          |""".stripMargin

    val stats = new InMemoryStatsReceiver
    val linker = Linker.load(config).configured(param.Stats(stats))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()
    val client = upstream(server)

    val req = Request()
    req.method = Method.Post
    req.host = "foo"
    await(client(req))

    assert(stats.counters.get(Seq("rt", "http", "service", "svc/foo", "success")) == Some(1))
    assert(stats.counters.get(Seq("rt", "http", "service", "svc/foo", "failures")) == None)
    assert(stats.counters.get(Seq("rt", "http", "client", s"$$/inet/127.1/${downstream.port}", "service", "svc/foo", "success")) == Some(1))
    assert(stats.counters.get(Seq("rt", "http", "client", s"$$/inet/127.1/${downstream.port}", "service", "svc/foo", "failures")) == None)
  }
}
