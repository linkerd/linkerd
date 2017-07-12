package io.buoyant.linkerd
package protocol

import com.twitter.conversions.time._
import com.twitter.finagle.buoyant.linkerd.Headers
import com.twitter.finagle.http.{Method, Request, Response}
import com.twitter.finagle.stats.{InMemoryStatsReceiver, NullStatsReceiver}
import com.twitter.finagle._
import com.twitter.io.Buf
import com.twitter.util.{Future, MockTimer, Time, Var}
import com.twitter.finagle.tracing.NullTracer
import io.buoyant.test.FunSuite
import java.net.InetSocketAddress

class RetriesEndToEndTest extends FunSuite {

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

  val writeException = new WriteException {}

  test("requeues") {
    var i = 0
    val downstream = Downstream("ds", Service.mk { req =>
      val rsp = i match {
        case 0 => Future.value(Response()) // first request
        case 1 => Future.exception(writeException)  // second request
        case 2 => Future.value(Response()) // second request (requeue)
        case 3 => Future.exception(writeException) // third request (budget exceeded)
      }
      i += 1
      rsp
    })

    val config =
      s"""|routers:
          |- protocol: http
          |  dtab: /svc/* => /$$/inet/127.1/${downstream.port}
          |  service:
          |    retries:
          |      budget:
          |        minRetriesPerSec: 0
          |        percentCanRetry: 0.0
          |  client:
          |    failFast: false
          |    failureAccrual:
          |      kind: none
          |    requeueBudget:
          |      minRetriesPerSec: 0
          |      # each request may generate 0.5 retries, on average
          |      percentCanRetry: 0.5
          |      ttlSecs: 10
          |  servers:
          |  - port: 0
          |""".stripMargin

    val stats = new InMemoryStatsReceiver
    val linker = Linker.load(config).configured(param.Stats(stats))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()
    val client = upstream(server)

    try {
      Time.withCurrentTimeFrozen { tc =>


        def budget = stats.gauges(Seq("rt", "http", "client", s"$$/inet/127.1/${downstream.port}", "retries", "budget"))
        def requeues = stats.counters.getOrElse(
          Seq("rt", "http", "client", s"$$/inet/127.1/${downstream.port}", "retries", "requeues"),
          0
        )
        def requestLimit = stats.counters.getOrElse(
          Seq("rt", "http", "client", s"$$/inet/127.1/${downstream.port}", "retries", "request_limit"),
          0
        )

        val req = Request()
        req.host = "foo"

        assert(await(client(req)).statusCode == 200) // first request (success)
        assert(budget() == 0) // (0.5).toInt
        assert(requeues == 0)
        assert(requestLimit == 0)

        assert(await(client(req)).statusCode == 200) // second request (success after a requeue)
        assert(budget() == 0)
        assert(requeues == 1)
        assert(requestLimit == 0)

        assert(await(client(req)).statusCode == 502) // third request (failed, no budget available for requeue)
        assert(budget() == 0) // (0.5).toInt
        assert(requeues == 1)
        assert(requestLimit == 1)

        // close the downstream
        await(downstream.server.close())
        assert(await(client(req)).statusCode == 502) // service acquisition failed
        assert(requeues == 26) // tried 25  times for service acquisition
      }
    } finally {
      await(client.close())
      await(downstream.server.close())
      await(server.close())
      await(router.close())
    }
  }

  test("per-client requeue policy") {

    class FailEveryN(n: Int) extends Service[Request, Response] {
      private[this] var i = 0
      def apply(req: Request): Future[Response] = {
        if (i%n == 0) {
          i += 1
          Future.exception(writeException)
        } else {
          i += 1
          Future.value(Response())
        }
      }
    }

    val downstreamA = Downstream("a", new FailEveryN(2))
    val downstreamB = Downstream("b", new FailEveryN(2))

    val config =
      s"""|routers:
          |- protocol: http
          |  dtab: |
          |    /svc/a => /$$/inet/127.1/${downstreamA.port} ;
          |    /svc/b => /$$/inet/127.1/${downstreamB.port} ;
          |  client:
          |    kind: io.l5d.static
          |    configs:
          |    - prefix: /
          |      failFast: false
          |      failureAccrual:
          |        kind: none
          |    - prefix: /$$/inet/127.1/${downstreamA.port}
          |      requeueBudget:
          |        minRetriesPerSec: 0
          |        # each request may generate 1.0 requeue, on average
          |        percentCanRetry: 1.0
          |        ttlSecs: 10
          |    - prefix: /$$/inet/127.1/${downstreamB.port}
          |      requeueBudget:
          |        minRetriesPerSec: 0
          |        # each request may generate 0.5 requeues, on average
          |        percentCanRetry: 0.5
          |        ttlSecs: 10
          |  servers:
          |  - port: 0
          |""".stripMargin

    val stats = new InMemoryStatsReceiver
    val linker = Linker.load(config).configured(param.Stats(stats))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()
    val client = upstream(server)

    try {
      Time.withCurrentTimeFrozen { tc =>

        def budget(ds: Downstream) =
          stats.gauges(Seq("rt", "http", "client", s"$$/inet/127.1/${ds.port}", "retries", "budget"))

        def requeues(ds: Downstream) = stats.counters.getOrElse(
          Seq("rt", "http", "client", s"$$/inet/127.1/${ds.port}", "retries", "requeues"),
          0
        )
        def requestLimit(ds: Downstream) = stats.counters.getOrElse(
          Seq("rt", "http", "client", s"$$/inet/127.1/${ds.port}", "retries", "request_limit"),
          0
        )

        val req = Request()
        req.host = "a"

        for (i <- 1 to 10) {
          // each request initially fails and then succeeds after 1 requeue
          assert(await(client(req)).statusCode == 200)
          assert(budget(downstreamA)() == 0)
          assert(requeues(downstreamA) == i)
          assert(requestLimit(downstreamA) == 0)
        }

        req.host = "b"
        // budget == 0
        assert(await(client(req)).statusCode == 502) // failure, no budget to requeue
        assert(budget(downstreamB)() == 0)
        assert(requeues(downstreamB) == 0)
        assert(requestLimit(downstreamB) == 1)

        // budget == 0.5
        assert(await(client(req)).statusCode == 200) // success on first try
        assert(budget(downstreamB)() == 1)
        assert(requeues(downstreamB) == 0)
        assert(requestLimit(downstreamB) == 1)

        // budget == 1
        assert(await(client(req)).statusCode == 200) // success after a requeue
        assert(budget(downstreamB)() == 0)
        assert(requeues(downstreamB) == 1)
        assert(requestLimit(downstreamB) == 1)

        // budget == 0.5
        assert(await(client(req)).statusCode == 200) // success after a requeue
        assert(budget(downstreamB)() == 0)
        assert(requeues(downstreamB) == 2)
        assert(requestLimit(downstreamB) == 1)

        // budget == 0
        assert(await(client(req)).statusCode == 502) // failure, no budget to requeue
        assert(budget(downstreamB)() == 0)
        assert(requeues(downstreamB) == 2)
        assert(requestLimit(downstreamB) == 2)
      }
    } finally {
      await(client.close())
      await(downstreamA.server.close())
      await(downstreamB.server.close())
      await(server.close())
      await(router.close())
    }
  }

  test("retries") {
    val success = Future.value(Response())
    val failure = Future.value {
      val rsp = Response()
      rsp.statusCode = 500
      rsp
    }
    var i = 0
    val downstream = Downstream("ds", Service.mk { req =>
      val rsp = i match {
        case 0 => success // first request
        case 1 => failure  // second request
        case 2 => success // second request (retry)
        case 3 => failure // third request (budget exceeded)
      }
      i += 1
      rsp
    })

    val config =
      s"""|routers:
          |- protocol: http
          |  dtab: /svc/* => /$$/inet/127.1/${downstream.port}
          |  client:
          |    failFast: false
          |    failureAccrual:
          |      kind: none
          |  service:
          |    responseClassifier:
          |      kind: io.l5d.http.retryableRead5XX
          |    retries:
          |      budget:
          |        minRetriesPerSec: 0
          |        # each request may generate 0.5 retries, on average
          |        percentCanRetry: 0.5
          |        ttlSecs: 10
          |  servers:
          |  - port: 0
          |""".stripMargin

    val stats = new InMemoryStatsReceiver
    val linker = Linker.load(config).configured(param.Stats(stats))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()
    val client = upstream(server)

    try {
      Time.withCurrentTimeFrozen { tc =>


        def budget = stats.gauges(Seq("rt", "http", "service", "svc/foo", "retries", "budget"))
        def retries = stats.counters.getOrElse(
          Seq("rt", "http", "service", "svc/foo", "retries", "total"),
          0
        )
        def budgetExhausted = stats.counters.getOrElse(
          Seq("rt", "http", "service", "svc/foo", "retries", "budget_exhausted"),
          0
        )

        val req = Request()
        req.host = "foo"

        assert(await(client(req)).statusCode == 200) // first request (success)
        assert(budget() == 0) // (0.5).toInt
        assert(retries == 0)
        assert(budgetExhausted == 0)

        assert(await(client(req)).statusCode == 200) // second request (success after a retry)
        assert(budget() == 0)
        assert(retries == 1)
        assert(budgetExhausted == 0)

        assert(await(client(req)).statusCode == 500) // third request (failed, no budget available for retry)
        assert(budget() == 0) // (0.5).toInt
        assert(retries == 1)
        assert(budgetExhausted == 1)
      }
    } finally {
      await(client.close())
      await(downstream.server.close())
      await(server.close())
      await(router.close())
    }
  }

  test("per-service retry policy") {

    class FailEveryN(n: Int) extends Service[Request, Response] {
      private[this] var i = 0
      def apply(req: Request): Future[Response] = {
        if (i%n == 0) {
          i += 1
          val rsp = Response()
          rsp.statusCode = 500
          Future.value(rsp)
        } else {
          i += 1
          Future.value(Response())
        }
      }
    }

    val downstreamA = Downstream("a", new FailEveryN(2))
    val downstreamB = Downstream("b", new FailEveryN(2))

    val config =
      s"""|routers:
          |- protocol: http
          |  dtab: |
          |    /svc/a => /$$/inet/127.1/${downstreamA.port} ;
          |    /svc/b => /$$/inet/127.1/${downstreamB.port} ;
          |  client:
          |    failFast: false
          |    failureAccrual:
          |      kind: none
          |  service:
          |    kind: io.l5d.static
          |    configs:
          |    - prefix: /svc/a
          |      retries:
          |        budget:
          |          minRetriesPerSec: 0
          |          # each request may generate 1.0 requeue, on average
          |          percentCanRetry: 1.0
          |          ttlSecs: 10
          |    - prefix: /svc/b
          |      retries:
          |        budget:
          |          minRetriesPerSec: 0
          |          # each request may generate 0.5 requeues, on average
          |          percentCanRetry: 0.5
          |          ttlSecs: 10
          |    - prefix: /
          |      responseClassifier:
          |        kind: io.l5d.http.retryableRead5XX
          |  servers:
          |  - port: 0
          |""".stripMargin

    val stats = new InMemoryStatsReceiver
    val linker = Linker.load(config).configured(param.Stats(stats))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()
    val client = upstream(server)

    try {
      Time.withCurrentTimeFrozen { tc =>

        def budget(ds: Downstream) =
          stats.gauges(Seq("rt", "http", "service", s"svc/${ds.name}", "retries", "budget"))

        def retries(ds: Downstream) = stats.counters.getOrElse(
          Seq("rt", "http", "service", s"svc/${ds.name}", "retries", "total"),
          0
        )
        def budgetExhausted(ds: Downstream) = stats.counters.getOrElse(
          Seq("rt", "http", "service", s"svc/${ds.name}", "retries", "budget_exhausted"),
          0
        )

        val req = Request()
        req.host = "a"

        for (i <- 1 to 10) {
          // each request initially fails and then succeeds after 1 retry
          assert(await(client(req)).statusCode == 200)
          assert(budget(downstreamA)() == 0)
          assert(retries(downstreamA) == i)
          assert(budgetExhausted(downstreamA) == 0)
        }

        req.host = "b"
        // budget == 0
        assert(await(client(req)).statusCode == 500) // failure, no budget to retry
        assert(budget(downstreamB)() == 0)
        assert(retries(downstreamB) == 0)
        assert(budgetExhausted(downstreamB) == 1)

        // budget == 0.5
        assert(await(client(req)).statusCode == 200) // success on first try
        assert(budget(downstreamB)() == 1)
        assert(retries(downstreamB) == 0)
        assert(budgetExhausted(downstreamB) == 1)

        // budget == 1
        assert(await(client(req)).statusCode == 200) // success after a retry
        assert(budget(downstreamB)() == 0)
        assert(retries(downstreamB) == 1)
        assert(budgetExhausted(downstreamB) == 1)

        // budget == 0.5
        assert(await(client(req)).statusCode == 200) // success after a retry
        assert(budget(downstreamB)() == 0)
        assert(retries(downstreamB) == 2)
        assert(budgetExhausted(downstreamB) == 1)

        // budget == 0
        assert(await(client(req)).statusCode == 500) // failure, no budget to retry
        assert(budget(downstreamB)() == 0)
        assert(retries(downstreamB) == 2)
        assert(budgetExhausted(downstreamB) == 2)
      }
    } finally {
      await(client.close())
      await(downstreamA.server.close())
      await(downstreamB.server.close())
      await(server.close())
      await(router.close())
    }
  }

  test("budgets for each service and client are independent") {

    val downstreamA = Downstream("a", Service.mk { req => Future.value(Response()) })
    val downstreamB = Downstream("b", Service.mk { req => Future.value(Response()) })

    val config =
      s"""|routers:
          |- protocol: http
          |  dtab: |
          |    /svc/a => /$$/inet/127.1/${downstreamA.port} ;
          |    /svc/b => /$$/inet/127.1/${downstreamB.port} ;
          |  servers:
          |  - port: 0
          |""".stripMargin

    val stats = new InMemoryStatsReceiver
    val linker = Linker.load(config).configured(param.Stats(stats))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()
    val client = upstream(server)

    try {
      Time.withCurrentTimeFrozen { tc =>
        val req = Request()
        req.host = "a"
        for (i <- 1 to 10) {
          await(client(req))
        }

        // Each budget starts with a balance of 100 from minRetriesPerSec
        // we subtract that off to see just the deposits
        def retryBudget(svc: String): Int =
          stats.gauges.get(Seq("rt", "http", "service", svc, "retries", "budget")).map(_() - 100).getOrElse(0.0f).toInt
        def requeueBudget(clnt: String): Int =
          stats.gauges.get(Seq("rt", "http", "client", clnt, "retries", "budget")).map(_() - 100).getOrElse(0.0f).toInt

        // 20% budget
        assert(retryBudget("svc/a") == 2)
        assert(retryBudget("svc/b") == 0)
        assert(requeueBudget(s"$$/inet/127.1/${downstreamA.port}") == 2)
        assert(requeueBudget(s"$$/inet/127.1/${downstreamB.port}") == 0)
        req.host = "b"
        for (i <- 1 to 10) {
          await(client(req))
        }

        assert(retryBudget("svc/a") == 2)
        assert(retryBudget("svc/b") == 2)
        assert(requeueBudget(s"$$/inet/127.1/${downstreamA.port}") == 2)
        assert(requeueBudget(s"$$/inet/127.1/${downstreamB.port}") == 2)
      }
    } finally {
      await(client.close())
      await(downstreamA.server.close())
      await(downstreamB.server.close())
      await(server.close())
      await(router.close())
    }
  }

  test("l5d-retryable header is respected by default") {
    var i = 0
    val downstream = Downstream("ds", Service.mk { req =>
      val rsp = i match {
        case 0 => Future.value {
          val rsp = Response()
          rsp.statusCode = 500
          Headers.Retryable.set(rsp.headerMap, retryable = true)
          rsp
        }
        case 1 => Future.value(Response())
      }
      i += 1
      rsp
    })

    val config = 
      s"""|routers:
          |- protocol: http
          |  dtab: /svc/* => /$$/inet/127.1/${downstream.port}
          |  servers:
          |  - port: 0
          |""".stripMargin

    val stats = new InMemoryStatsReceiver
    val linker = Linker.load(config).configured(param.Stats(stats))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()
    val client = upstream(server)

    try {
      Time.withCurrentTimeFrozen { tc =>

        def retries = stats.counters.getOrElse(
          Seq("rt", "http", "service", "svc/foo", "retries", "total"),
          0
        )

        val req = Request()
        req.host = "foo"

        assert(await(client(req)).statusCode == 200)
        assert(retries == 1)
      }
    } finally {
      await(client.close())
      await(downstream.server.close())
      await(server.close())
      await(router.close())
    }
  }

  test("l5d-retryable header takes precedence over repsonse classifier") {
    var i = 0
    val downstream = Downstream("ds", Service.mk { req =>
      val rsp = i match {
        case 0 => Future.value {
          val rsp = Response()
          rsp.statusCode = 500
          Headers.Retryable.set(rsp.headerMap, retryable = true)
          rsp
        }
        case 1 => Future.value(Response())
      }
      i += 1
      rsp
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

    try {
      Time.withCurrentTimeFrozen { tc =>

        def retries = stats.counters.getOrElse(
          Seq("rt", "http", "service", "svc/foo", "retries", "total"),
          0
        )

        val req = Request()
        req.method = Method.Post
        req.host = "foo"

        // POST should not usually be retryable, but l5d-retryable indicates
        // the request can be retried anyway
        assert(await(client(req)).statusCode == 200)
        assert(retries == 1)
      }
    } finally {
      await(client.close())
      await(downstream.server.close())
      await(server.close())
      await(router.close())
    }
  }

  test("chunked error responses should not leak connections on retries") {
    val stats = new InMemoryStatsReceiver
    val tracer = NullTracer

    val downstream = Downstream("dog", Service.mk { req =>
      val rsp = Response()
      rsp.statusCode = 500
      rsp.setChunked(true)
      rsp.close()
      Future.value(rsp)
    })

    val label = s"$$/inet/127.1/${downstream.port}"
    val dtab = Dtab.read(s"/svc/dog => /$label;")
    val yaml =
      s"""|routers:
          |- protocol: http
          |  dtab: ${dtab.show}
          |  service:
          |    responseClassifier:
          |      kind: io.l5d.http.retryableRead5XX
          |  servers:
          |  - port: 0
          |""".stripMargin
    val linker = Linker.load(yaml)
      .configured(param.Stats(stats))
      .configured(param.Tracer(tracer))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()
    val client = upstream(server)

    try {

      val req = Request()
      req.host = "dog"
      val errrsp = await(client(req))
      assert(errrsp.statusCode == 500)
      assert(stats.counters.get(Seq("rt", "http", "server", "127.0.0.1/0", "requests")) == Some(1))
      assert(stats.counters.get(Seq("rt", "http", "client", label, "requests")) == Some(101))
      assert(stats.gauges.get(Seq("rt", "http", "client", label, "connections")).map(_.apply.toInt) == Some(1))

    } finally {
      await(client.close())
      await(downstream.server.close())
      await(server.close())
      await(router.close())
    }

  }

  test("individual request timeouts should be retried") {
    val stats = new InMemoryStatsReceiver
    val tracer = NullTracer
    val timer = new MockTimer

    @volatile var i = 0

    val downstream = Downstream("dog", Service.mk { req =>
      if (i == 0) {
        i += 1
        Future.sleep(2.seconds)(timer).map(_ => Response())
      } else {
        val rsp = Response()
        Future.value(rsp)
      }
    })

    val label = s"$$/inet/127.1/${downstream.port}"
    val dtab = Dtab.read(s"/svc/dog => /$label;")
    val yaml =
      s"""|routers:
          |- protocol: http
          |  dtab: ${dtab.show}
          |  service:
          |    responseClassifier:
          |      kind: io.l5d.http.retryableRead5XX
          |  client:
          |    requestAttemptTimeoutMs: 1000
          |  servers:
          |  - port: 0
          |""".stripMargin
    val linker = Linker.load(yaml)
      .configured(param.Stats(stats))
      .configured(param.Tracer(tracer))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()
    val client = upstream(server)

    try {

      val req = Request()
      req.host = "dog"
      Time.withCurrentTimeFrozen { tc =>
        val rspF = client(req)
        tc.advance(3.seconds)
        timer.tick()
        val rsp = await(rspF)
        assert(rsp.statusCode == 200)
        assert(stats.counters.get(Seq("rt", "http", "server", "127.0.0.1/0", "requests")) == Some(1))
        assert(stats.counters.get(Seq("rt", "http", "client", label, "requests")) == Some(2))
        assert(stats.counters.get(Seq("rt", "http", "service", s"svc/dog", "retries", "total")) == Some(1))
      }
    } finally {
      await(client.close())
      await(downstream.server.close())
      await(server.close())
      await(router.close())
    }

  }
}
