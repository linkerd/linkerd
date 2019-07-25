package io.buoyant.linkerd.protocol.h2

import com.twitter.concurrent.AsyncQueue
import com.twitter.conversions.DurationOps._
import com.twitter.finagle.Path
import com.twitter.finagle.buoyant.h2._
import com.twitter.finagle.param.Stats
import com.twitter.finagle.service.ExpiringService
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.logging.Level
import com.twitter.util.{Duration, Future, Throw}
import io.buoyant.linkerd.protocol.H2Initializer
import io.buoyant.linkerd.{Linker, Router}
import io.buoyant.router.StackRouter.Client.PerClientParams
import io.buoyant.test.FunSuite
import io.buoyant.test.h2.StreamTestUtils._
import java.io.File
import java.nio.charset.StandardCharsets
import org.scalatest.time.{Millis, Seconds, Span}
import scala.io.Source
import scala.util.Random

class H2EndToEndTest extends FunSuite {

  test("single request") {
    val stats = new InMemoryStatsReceiver

    val dog = Downstream.const("dog", "woof")

    val config =
      s"""|routers:
          |- protocol: h2
          |  experimental: true
          |  dtab: |
          |    /svc/dog => /$$/inet/127.1/${dog.port} ;
          |  servers:
          |  - port: 0
          |""".stripMargin

    val linker = Linker.Initializers(Seq(H2Initializer)).load(config)
      .configured(Stats(stats))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()

    val client = Upstream.mk(server)
    def get(host: String, path: String = "/")(f: Response => Unit) = {
      val req = Request("http", Method.Get, host, path, Stream.empty())
      val rsp = await(client(req))
      f(rsp)
    }

    get("dog") { rsp =>
      assert(rsp.status == Status.Ok)
      assert(await(rsp.stream.readDataString) == "woof")
      ()
    }
    assert(stats.counters(Seq("rt", "h2", "client", s"$$/inet/127.1/${dog.port}", "connects")) == 1)

    await(client.close())
    await(dog.server.close())
    await(server.close())
    await(router.close())
  }

  test("concurrent requests") {
    val stats = new InMemoryStatsReceiver
    val (dog, rsps) = Downstream.promise("dog")

    val config =
      s"""|routers:
          |- protocol: h2
          |  experimental: true
          |  dtab: |
          |    /svc/dog => /$$/inet/127.1/${dog.port} ;
          |  servers:
          |  - port: 0
          |""".stripMargin

    val linker = Linker.Initializers(Seq(H2Initializer)).load(config)
      .configured(Stats(stats))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()

    val client = Upstream.mk(server)


    val req0 = Request("http", Method.Get, "dog", "/", Stream.empty())
    val fRsp0 = client(req0)

    eventually { // wait for the promise to be generated
      assert(rsps.size == 1)
    }

    val req1 = Request("http", Method.Get, "dog", "/", Stream.empty())
    val fRsp1 = client(req1)

    eventually { // wait for the promise to be generated
      assert(rsps.size == 2)
    }

    rsps(1).setValue(Response(Status.Ok, Stream.const("bow")))
    val rsp1 = await(fRsp1)
    assert(await(rsp1.stream.readDataString) == "bow")

    rsps(0).setValue(Response(Status.Ok, Stream.const("wow")))
    val rsp0 = await(fRsp0)
    assert(await(rsp0.stream.readDataString) == "wow")

    // should multiplex over a single connection
    assert(stats.counters(Seq("rt", "h2", "client", s"$$/inet/127.1/${dog.port}", "connects")) == 1)

    await(client.close())
    await(dog.server.close())
    await(server.close())
    await(router.close())
  }

  test("wait for stream to complete before closing") {
    val stats = new InMemoryStatsReceiver
    val (dog, rsps) = Downstream.promise("dog")

    val config =
      s"""|routers:
          |- protocol: h2
          |  experimental: true
          |  dtab: |
          |    /svc/dog => /$$/inet/127.1/${dog.port} ;
          |  servers:
          |  - port: 0
          |""".stripMargin

    val linker = Linker.Initializers(Seq(H2Initializer)).load(config)
      .configured(Stats(stats))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()

    val client = Upstream.mk(server)

    val req = Request("http", Method.Get, "dog", "/", Stream.empty())
    val fRsp = client(req)

    eventually { // wait for the promise to be generated
      assert(rsps.size == 1)
    }

    val q = new AsyncQueue[Frame]()
    val stream = Stream(q)

    rsps(0).setValue(Response(Status.Ok, stream))

    q.offer(Frame.Data("bow", eos = false))
    q.offer(Frame.Data("wow", eos = true))

    val rsp = await(fRsp)

    assert(await(rsp.stream.readDataString) == "bowwow")

    await(client.close())
    await(server.close())
    await(router.close())
    await(dog.server.close())
  }

  test("logs to correct files") {
    val stats = new InMemoryStatsReceiver

    val dog = Downstream.const("dog", "woof")

    val logs = Array(
      File.createTempFile("access", "log0"),
      File.createTempFile("access", "log1")
    )
    logs.foreach { log => log.deleteOnExit() }

    def randomPort = 32000 + (Random.nextDouble * 30000).toInt

    val config =
      s"""|routers:
          |- protocol: h2
          |  label: router0
          |  h2AccessLog: ${logs(0).getPath}
          |  experimental: true
          |  dtab: |
          |    /svc/dog => /$$/inet/127.1/${dog.port} ;
          |  servers:
          |  - port: ${randomPort}
          |- protocol: h2
          |  label: router1
          |  h2AccessLog: ${logs(1).getPath}
          |  experimental: true
          |  dtab: |
          |    /svc/dog => /$$/inet/127.1/${dog.port} ;
          |  servers:
          |  - port: ${randomPort}
          |""".stripMargin

    val linker = Linker.Initializers(Seq(H2Initializer)).load(config)
      .configured(Stats(stats))

    val routers = linker.routers.map { router =>
      router.initialize()
    }

    try {
      Array("/path0", "/path1", "/path2", "/path3").zipWithIndex.foreach {
        case (path, i) =>
          val routerIndex = i%2
          val server = routers(routerIndex).servers.head.serve()

          val client = Upstream.mk(server)
          def get(host: String, path: String = path)(f: Response => Unit) = {
            val req = Request("http", Method.Get, host, path, Stream.empty())
            val rsp = await(client(req))
            f(rsp)
          }

          try {
            get("dog") { rsp =>
              assert(rsp.status == Status.Ok)
              assert(await(rsp.stream.readDataString) == "woof")
              ()
            }
          } finally {
            await(client.close())
            await(server.close())
          }

          val source = Source.fromFile(logs(routerIndex))
          val lines = try source.mkString finally source.close()
          assert(lines.contains(path))
      }
    } finally {
      await(dog.server.close())
      routers.foreach { router => await(router.close()) }
    }
  }

  test("clientSession idleTimeMs should close client connections") {
    val config =
      s"""|routers:
          |- protocol: h2
          |  experimental: true
          |  dtab: |
          |    /svc/dog => /$$/inet/127.1/{dog.port} ;
          |  servers:
          |  - port: 0
          |  client:
          |    clientSession:
          |      idleTimeMs: 1500
          |""".stripMargin

    idleTimeMsBaseTest(config){ (router:Router.Initialized, stats:InMemoryStatsReceiver, dogPort:Int) =>

      // Assert
      def activeConnectionsCount = stats.gauges(Seq("rt", "h2", "client", s"$$/inet/127.1/${dogPort}", "connections"))

      // An incoming request through the H2.Router will establish an active connection; We expect to see it here
      assert(activeConnectionsCount() == 1.0)

      eventually(timeout(Span(5, Seconds)), interval(Span(250, Millis))) {
        val cnt = activeConnectionsCount()
        assert(cnt == 0.0)
      }

      val clientSessionParams = router.params[PerClientParams].paramsFor(Path.read("/svc/dog"))[ExpiringService.Param]
      assert(clientSessionParams.idleTime == 1500.milliseconds)
      assert(clientSessionParams.lifeTime == Duration.Top)

      ()
    }
  }

  test("clientSession idleTimeMs should close client connections for static client") {
    val config =
      s"""|routers:
          |- protocol: h2
          |  experimental: true
          |  dtab: |
          |    /svc/dog => /$$/inet/127.1/{dog.port} ;
          |  servers:
          |  - port: 0
          |  client:
          |    kind: io.l5d.static
          |    configs:
          |      - prefix: /*
          |        clientSession:
          |          idleTimeMs: 1500
          |""".stripMargin

    idleTimeMsBaseTest(config) { (router: Router.Initialized, stats: InMemoryStatsReceiver, dogPort: Int) =>

      // Assert
      def activeConnectionsCount = stats.gauges(Seq("rt", "h2", "client", s"$$/inet/127.1/${dogPort}", "connections"))

      // An incoming request through the H2.Router will establish an active connection; We expect to see it here
      assert(activeConnectionsCount() == 1.0)

      eventually(timeout(Span(5, Seconds)), interval(Span(250, Millis))) {
        val cnt = activeConnectionsCount()
        assert(cnt == 0.0)
      }

      val clientSessionParams = router.params[PerClientParams].paramsFor(Path.read("/svc/dog"))[ExpiringService.Param]
      assert(clientSessionParams.idleTime == 1500.milliseconds)
      assert(clientSessionParams.lifeTime == Duration.Top)

      ()
    }
  }

  test("clientSession idleTimeMs should not close client connections when isn't specified") {
    val config =
      s"""|routers:
          |- protocol: h2
          |  experimental: true
          |  dtab: |
          |    /svc/dog => /$$/inet/127.1/{dog.port} ;
          |  servers:
          |  - port: 0
          |  client:
          |    forwardClientCert: false
          |""".stripMargin

    idleTimeMsBaseTest(config) { (router: Router.Initialized, stats: InMemoryStatsReceiver, dogPort: Int) =>
      // Assert
      def activeConnectionsCount = stats.gauges(Seq("rt", "h2", "client", s"$$/inet/127.1/${dogPort}", "connections"))

      // An incoming request through the H2.Router will establish an active connection; We expect to see it here
      assert(activeConnectionsCount() == 1.0)

      val clientSessionParams = router.params[PerClientParams].paramsFor(Path.read("/svc/dog"))[ExpiringService.Param]
      assert(clientSessionParams.idleTime == Duration.Top)
      assert(clientSessionParams.lifeTime == Duration.Top)

      ()
    }
  }

  def idleTimeMsBaseTest(config:String)(assertionsF: (Router.Initialized, InMemoryStatsReceiver, Int) => Unit): Unit = {
    // Arrange
    val stats = new InMemoryStatsReceiver

    val dog = Downstream.const("dog", "woof")

    val configWithPort = config.replace("{dog.port}", dog.port.toString)

    val linker = Linker.Initializers(Seq(H2Initializer)).load(configWithPort)
      .configured(Stats(stats))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()

    val client = Upstream.mk(server)
    def get(host: String, path: String = "/")(f: Response => Unit) = {
      val req = Request("http", Method.Get, host, path, Stream.empty())
      val rsp = await(client(req))
      f(rsp)
    }

    // Act
    try {
      // This will force linkerd to open a connection to the `dog` service and hold it
      get("dog") { rsp =>
        assert(rsp.status == Status.Ok)
        assert(await(rsp.stream.readDataString) == "woof")
        ()
      }

      // Assert
      assertionsF(router, stats, dog.port)

    } finally {
      await(client.close())
      await(dog.server.close())
      await(server.close())
      await(router.close())
    }
  }

  test("client resets server stream") {
    val q = new AsyncQueue[Frame]()
    val stream = Stream(q)

    val dog = Downstream.mk("dog") { req =>
      Future.value(Response(Status.Ok, stream))
    }

    val config =
      s"""|routers:
          |- protocol: h2
          |  experimental: true
          |  dtab: |
          |    /svc/dog => /$$/inet/127.1/${dog.port} ;
          |  servers:
          |  - port: 0
          |""".stripMargin

    val linker = Linker.Initializers(Seq(H2Initializer)).load(config)
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()
    val client = Upstream.mk(server)

    val req = Request("http", Method.Get, "dog", "/", Stream.empty())
    val rsp = await(client(req))

    assert(rsp.status == Status.Ok)
    assert(q.offer(Frame.Data("one", eos = false)))
    val frame = await(rsp.stream.read())
    val s = frame.asInstanceOf[Frame.Data].buf.toString(StandardCharsets.UTF_8)
    assert(s == "one")

    rsp.stream.cancel(Reset.Cancel)
    val result = await(rsp.stream.read().liftToTry)
    assert(result == Throw(Reset.Cancel))
    eventually {
      assert(!q.offer(Frame.Data("reject me", eos = false)))
    }

    await(client.close())
    await(dog.server.close())
    await(server.close())
    await(router.close())
  }

  test("server resets client stream") {
    setLogLevel(Level.TRACE)
    val dog = Downstream.mk("dog") { req =>
      req.stream.cancel(Reset.Cancel)
      Future.value(Response(Status.Ok, Stream.empty))
    }

    val config =
      s"""|routers:
          |- protocol: h2
          |  experimental: true
          |  dtab: |
          |    /svc/dog => /$$/inet/127.1/${dog.port} ;
          |  servers:
          |  - port: 0
          |""".stripMargin

    val linker = Linker.Initializers(Seq(H2Initializer)).load(config)
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()
    val client = Upstream.mk(server)

    val q = new AsyncQueue[Frame]()
    val stream = Stream(q)

    val req = Request("http", Method.Get, "dog", "/", stream)
    val rsp = await(client(req).liftToTry)

    assert(rsp == Throw(Reset.Cancel))
    assert(!q.offer(Frame.Data("reject me", eos = false)))

    await(client.close())
    await(dog.server.close())
    await(server.close())
    await(router.close())
    setLogLevel(Level.OFF)
  }

  test("context headers") {

    val dog = Downstream.mk("dog") { req =>
      assert(req.headers.get(LinkerdHeaders.Ctx.Dtab.CtxKey) == Some("/foo=>/bar"))
      assert(req.headers.contains(LinkerdHeaders.Ctx.Trace.Key))
      Future.value(Response(Status.Ok, Stream.const("woof")))
    }

    val config =
      s"""|routers:
          |- protocol: h2
          |  dtab: |
          |    /svc/dog => /$$/inet/127.1/${dog.port} ;
          |  servers:
          |  - port: 0
          |""".stripMargin

    val linker = Linker.Initializers(Seq(H2Initializer)).load(config)
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()
    val client = Upstream.mk(server)
    val req = Request(Headers(
      Headers.Authority -> "dog",
      Headers.Path -> "/",
      Headers.Method -> "get",
      LinkerdHeaders.Ctx.Dtab.UserKey -> "/foo=>/bar"
    ), Stream.empty())
    val rsp = await(client(req))

    assert(rsp.status == Status.Ok)
    assert(await(rsp.stream.readDataString) == "woof")

    await(client.close())
    await(dog.server.close())
    await(server.close())
    await(router.close())
  }

  test("diagnostic tracing with h2 request"){
    val dog = Downstream.const("dog", "woof")
    val config =
      s"""|routers:
          |- protocol: h2
          |  dtab: |
          |    /svc/dog => /$$/inet/127.1/${dog.port} ;
          |  servers:
          |  - port: 0
          |""".stripMargin

    val linker = Linker.Initializers(Seq(H2Initializer)).load(config)
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()

    val client = Upstream.mk(server)

    def trace(host: String, path: String = "/")(f: Response => Unit) = {
      val req = Request("http", Method.Trace, host, path, Stream.empty)
      req.headers.set("l5d-add-context", "true")
      val rsp = await(client(req))
      f(rsp)
    }

    try {
      val content = s"""|service name: /svc/dog
                        |client name: /$$/inet/127.1/${dog.port}
                        |addresses: [127.0.0.1:${dog.port}]
                        |selected address: 127.0.0.1:${dog.port}
                        |dtab resolution:
                        |  /svc/dog
                        |  /$$/inet/127.1/${dog.port} (/svc/dog=>/$$/inet/127.1/${dog.port})
                        |""".stripMargin
      trace("dog") { rsp =>
        assert(rsp.status == Status.Ok)
        // assertion is a 'contains' instead of 'equal' since the full response stream contains
        // dynamically generated text i.e. request duration.
        assert(await(rsp.stream.readDataString).contains(content))
        ()
      }
    } finally {
      await(client.close())
      await(server.close())
      await(dog.server.close())
      await(router.close())
    }
  }


  test("returns 400 for requests that have more than allowed hops") {
    val config =
      s"""|routers:
          |- protocol: h2
          |  servers:
          |  - port: 0
          |    maxCallDepth: 2
          |""".stripMargin

    val linker = Linker.Initializers(Seq(H2Initializer)).load(config)
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()
    val client = Upstream.mk(server)

    val req = Request(Headers(
      Headers.Via -> "hop1, hop2, hop3",
      Headers.Authority -> "dog",
      Headers.Path -> "/",
      Headers.Method -> "get",
      LinkerdHeaders.Ctx.Dtab.UserKey -> "/foo=>/bar"
    ), Stream.empty())

    val rsp = await(client(req))
    val expectedMessage = "Maximum number of calls (2) has been exceeded. Please check for proxy loops."
    assert(rsp.status == Status.BadRequest)
    assert(await(rsp.stream.readDataString) == expectedMessage)

    await(client.close())
    await(server.close())
    await(router.close())
  }

}
