package io.buoyant.linkerd.protocol.h2

import com.twitter.concurrent.AsyncQueue
import com.twitter.conversions.time._
import com.twitter.finagle.buoyant.h2._
import com.twitter.finagle.param.Stats
import com.twitter.finagle.Path
import com.twitter.finagle.service.ExpiringService
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.util.Duration
import io.buoyant.linkerd.{Linker, Router}
import io.buoyant.linkerd.protocol.H2Initializer
import io.buoyant.router.StackRouter.Client.PerClientParams
import io.buoyant.test.FunSuite
import io.buoyant.test.h2.StreamTestUtils._
import java.io.File
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


}
