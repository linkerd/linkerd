package io.buoyant.linkerd
package protocol

import com.twitter.conversions.time._
import com.twitter.finagle.{Http => FinagleHttp, Status => _, http => _, _}
import com.twitter.finagle.buoyant.linkerd.Headers
import com.twitter.finagle.http.{param => _, _}
import com.twitter.finagle.http.Method._
import com.twitter.finagle.stats.{InMemoryStatsReceiver, NullStatsReceiver}
import com.twitter.finagle.tracing.{Annotation, BufferingTracer, NullTracer}
import com.twitter.util._
import io.buoyant.router.{Http, RoutingFactory}
import io.buoyant.router.http.MethodAndHostIdentifier
import io.buoyant.test.Awaits
import java.net.InetSocketAddress
import org.scalatest.FunSuite

import org.scalatest.{FunSuite, MustMatchers}

class HttpEndToEndTest extends FunSuite with Awaits with MustMatchers {

  case class Downstream(name: String, server: ListeningServer) {
    val address = server.boundAddress.asInstanceOf[InetSocketAddress]
    val port = address.getPort
    val dentry = Dentry(
      Path.read(s"/svs/$name"),
      NameTree.read(s"/$$/inet/127.1/$port")
    )
  }

  object Downstream {
    def mk(name: String)(f: Request=>Response): Downstream = {
      val service = Service.mk { req: Request => Future(f(req)) }
      val stack = FinagleHttp.server.stack.remove(Headers.Ctx.serverModule.role)
      val server = FinagleHttp.server.withStack(stack)
        .configured(param.Label(name))
        .configured(param.Tracer(NullTracer))
        .serve(":*", service)
      Downstream(name, server)
    }

    def const(name: String, value: String, status: Status = Status.Ok): Downstream =
      mk(name) { _ =>
        val rsp = Response()
        rsp.status = status
        rsp.contentString = value
        rsp
      }
  }

  def upstream(server: ListeningServer) = {
    val address = Address(server.boundAddress.asInstanceOf[InetSocketAddress])
    val name = Name.Bound(Var.value(Addr.Bound(address)), address)
    val stack = FinagleHttp.client.stack.remove(Headers.Ctx.clientModule.role)
    FinagleHttp.client.withStack(stack)
      .configured(param.Stats(NullStatsReceiver))
      .configured(param.Tracer(NullTracer))
      .newClient(name, "upstream").toService
  }

  def basicConfig(dtab: Dtab) =
    s"""|routers:
        |- protocol: http
        |  dtab: ${dtab.show}
        |  servers:
        |  - port: 0
        |""".stripMargin

  def annotationKeys(annotations: Seq[Annotation]): Seq[String] =
    annotations.collect {
      case Annotation.ClientSend() => "cs"
      case Annotation.ClientRecv() => "cr"
      case Annotation.ServerSend() => "ss"
      case Annotation.ServerRecv() => "sr"
      case Annotation.WireSend => "ws"
      case Annotation.WireRecv => "wr"
      case Annotation.BinaryAnnotation(k, _) if k == "l5d.success" => k
      case Annotation.Message(m) if Seq("l5d.retryable", "l5d.failure").contains(m) => m
    }

  test("linking") {
    val stats = NullStatsReceiver
    val tracer = new BufferingTracer
    def withAnnotations(f: Seq[Annotation] => Unit): Unit = {
      f(tracer.iterator.map(_.annotation).toSeq)
      tracer.clear()
    }

    val cat = Downstream.const("cat", "meow")
    val dog = Downstream.const("dog", "woof")
    val dtab = Dtab.read(s"""
      /p/cat => /$$/inet/127.1/${cat.port} ;
      /p/dog => /$$/inet/127.1/${dog.port} ;
      /svc/felix => /p/cat ;
      /svc/clifford => /p/dog ;
    """)

    val linker = Linker.Initializers(Seq(HttpInitializer)).load(basicConfig(dtab))
      .configured(param.Stats(stats))
      .configured(param.Tracer(tracer))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()

    val client = upstream(server)
    def get(host: String, path: String = "/")(f: Response => Unit): Unit = {
      val req = Request()
      req.host = host
      req.uri = path
      val rsp = await(client(req))
      f(rsp)
    }

    try {
      get("felix") { rsp =>
        assert(rsp.status == Status.Ok)
        assert(rsp.contentString == "meow")

        val path = "/svc/felix"
        val bound = s"/$$/inet/127.1/${cat.port}"
        withAnnotations { anns =>
          assert(annotationKeys(anns) == Seq("sr", "cs", "ws", "wr", "l5d.success", "cr", "ss"))
          assert(anns.contains(Annotation.BinaryAnnotation("service", path)))
          assert(anns.contains(Annotation.BinaryAnnotation("client", bound)))
          assert(anns.contains(Annotation.BinaryAnnotation("residual", "/")))
          ()
        }
      }

      get("ralph-machio") { rsp =>
        assert(rsp.status == Status.BadGateway)
        assert(rsp.headerMap.contains(Headers.Err.Key))
        ()
      }

      get("") { rsp =>
        assert(rsp.status == Status.BadRequest)
        assert(rsp.headerMap.contains(Headers.Err.Key))
        ()
      }

      // todo check stats
    } finally {
      await(client.close())
      await(cat.server.close())
      await(dog.server.close())
      await(server.close())
      await(router.close())
    }
  }


  test("marks 5XX as failure by default") {
    val stats = new InMemoryStatsReceiver
    val tracer = NullTracer

    val downstream = Downstream.mk("dog") {
      case req if req.path == "/woof" =>
        val rsp = Response()
        rsp.status = Status.Ok
        rsp.contentString = "woof"
        rsp
      case _ =>
        val rsp = Response()
        rsp.status = Status.InternalServerError
        rsp
    }

    val label = s"$$/inet/127.1/${downstream.port}"
    val dtab = Dtab.read(s"/svc/dog => /$label;")

    val linker = Linker.Initializers(Seq(HttpInitializer)).load(basicConfig(dtab))
      .configured(param.Stats(stats))
      .configured(param.Tracer(tracer))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()
    val client = upstream(server)
    try {
      val okreq = Request()
      okreq.host = "dog"
      okreq.uri = "/woof"
      val okrsp = await(client(okreq))
      assert(okrsp.status == Status.Ok)
      assert(stats.counters.get(Seq("rt", "http", "server", "127.0.0.1/0", "requests")) == Some(1))
      assert(stats.counters.get(Seq("rt", "http", "server", "127.0.0.1/0", "success")) == Some(1))
      assert(stats.counters.get(Seq("rt", "http", "server", "127.0.0.1/0", "failures")) == None)
      assert(stats.counters.get(Seq("rt", "http", "client", label, "requests")) == Some(1))
      assert(stats.counters.get(Seq("rt", "http", "client", label, "success")) == Some(1))
      assert(stats.counters.get(Seq("rt", "http", "client", label, "failures")) == None)

      val errreq = Request()
      errreq.host = "dog"
      val errrsp = await(client(errreq))
      assert(errrsp.status == Status.InternalServerError)
      assert(stats.counters.get(Seq("rt", "http", "server", "127.0.0.1/0", "requests")) == Some(2))
      assert(stats.counters.get(Seq("rt", "http", "server", "127.0.0.1/0", "success")) == Some(1))
      assert(stats.counters.get(Seq("rt", "http", "server", "127.0.0.1/0", "failures")) == Some(1))
      assert(stats.counters.get(Seq("rt", "http", "client", label, "requests")) == Some(2))
      assert(stats.counters.get(Seq("rt", "http", "client", label, "success")) == Some(1))
      assert(stats.counters.get(Seq("rt", "http", "client", label, "failures")) == Some(1))

    } finally {
      await(client.close())
      await(downstream.server.close())
      await(server.close())
      await(router.close())
    }
  }

  test("marks exceptions as failure by default") {
    val stats = new InMemoryStatsReceiver
    val tracer = NullTracer

    val downstream = Downstream.mk("dog") { req => ??? }

    val label = s"$$/inet/127.1/${downstream.port}"
    val dtab = Dtab.read(s"/svc/dog => /$label;")

    val linker = Linker.Initializers(Seq(HttpInitializer)).load(basicConfig(dtab))
      .configured(param.Stats(stats))
      .configured(param.Tracer(tracer))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()
    val client = upstream(server)

    // Just close the downstream right away to generate connection exceptions
    await(downstream.server.close())

    try {
      val req = Request()
      req.host = "dog"
      val rsp = await(client(req))
      assert(rsp.status == Status.BadGateway)
      assert(stats.counters.get(Seq("rt", "http", "server", "127.0.0.1/0", "requests")) == Some(1))
      assert(stats.counters.get(Seq("rt", "http", "server", "127.0.0.1/0", "success")) == None)
      assert(stats.counters.get(Seq("rt", "http", "server", "127.0.0.1/0", "failures")) == Some(1))
      assert(stats.counters.get(Seq("rt", "http", "service", "svc/dog", "requests")) == Some(1))
      assert(stats.counters.get(Seq("rt", "http", "service", "svc/dog", "success")) == None)
      assert(stats.counters.get(Seq("rt", "http", "service", "svc/dog", "failures")) == Some(1))
    } finally {
      await(client.close())
      await(downstream.server.close())
      await(server.close())
      await(router.close())
    }
  }

  val allMethods = Set[Method](Connect, Delete, Get, Head, Patch, Post, Put, Options, Trace)
  val readMethods = Set[Method](Get, Head, Options, Trace)
  val idempotentMethods = readMethods ++ Set[Method](Delete, Put)

  def retryTest(kind: String, methods: Set[Method]): Unit = {
    val stats = new InMemoryStatsReceiver
    val tracer = new BufferingTracer
    def withAnnotations(f: Seq[Annotation] => Unit): Unit = {
      f(tracer.iterator.map(_.annotation).toSeq)
      tracer.clear()
    }

    @volatile var failNext = false
    val downstream = Downstream.mk("dog") { req =>
      val rsp = Response()
      rsp.status = if (failNext) Status.InternalServerError else Status.Ok
      failNext = false
      rsp
    }

    val label = s"$$/inet/127.1/${downstream.port}"
    val dtab = Dtab.read(s"/svc/dog => /$label;")
    val yaml =
      s"""|routers:
          |- protocol: http
          |  dtab: ${dtab.show}
          |  service:
          |    responseClassifier:
          |      kind: $kind
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
      // retryable request, fails and is retried
      for (method <- methods) {
        val req = Request()
        req.method = method
        req.host = "dog"
        failNext = true
        stats.clear()
        val rsp = await(client(req))
        assert(rsp.status == Status.Ok)
        assert(stats.counters.get(Seq("rt", "http", "server", "127.0.0.1/0", "requests")) == Some(1))
        assert(stats.counters.get(Seq("rt", "http", "server", "127.0.0.1/0", "success")) == Some(1))
        assert(stats.counters.get(Seq("rt", "http", "server", "127.0.0.1/0", "failures")) == None)
        assert(stats.counters.get(Seq("rt", "http", "client", label, "requests")) == Some(2))
        assert(stats.counters.get(Seq("rt", "http", "client", label, "success")) == Some(1))
        assert(stats.counters.get(Seq("rt", "http", "client", label, "failures")) == Some(1))
        assert(stats.counters.get(Seq("rt", "http", "client", label, "status", "200")) == Some(1))
        assert(stats.counters.get(Seq("rt", "http", "client", label, "status", "500")) == Some(1))
        val name = "svc/dog"
        assert(stats.counters.get(Seq("rt", "http", "service", name, "requests")) == Some(1))
        assert(stats.counters.get(Seq("rt", "http", "service", name, "success")) == Some(1))
        assert(stats.counters.get(Seq("rt", "http", "service", name, "failures")) == None)
        assert(stats.stats.get(Seq("rt", "http", "service", name, "retries", "per_request")) == Some(Seq(1.0)))
        assert(stats.counters.get(Seq("rt", "http", "service", name, "retries", "total")) == Some(1))
        withAnnotations { anns =>
          assert(annotationKeys(anns) == Seq("sr", "cs", "ws", "wr", "l5d.retryable", "cr", "cs", "ws", "wr", "l5d.success", "cr", "ss"))
          ()
        }
      }

      // non-retryable request, fails and is not retried
      for (method <- allMethods -- methods) {
        val req = Request()
        req.method = method
        req.host = "dog"
        failNext = true
        stats.clear()
        val rsp = await(client(req))
        assert(rsp.status == Status.InternalServerError)
        assert(stats.counters.get(Seq("rt", "http", "server", "127.0.0.1/0", "requests")) == Some(1))
        assert(stats.counters.get(Seq("rt", "http", "server", "127.0.0.1/0", "success")) == None)
        assert(stats.counters.get(Seq("rt", "http", "server", "127.0.0.1/0", "failures")) == Some(1))
        assert(stats.counters.get(Seq("rt", "http", "client", label, "requests")) == Some(1))
        assert(stats.counters.get(Seq("rt", "http", "client", label, "success")) == None)
        assert(stats.counters.get(Seq("rt", "http", "client", label, "failures")) == Some(1))
        assert(stats.counters.get(Seq("rt", "http", "client", label, "status", "200")) == None)
        assert(stats.counters.get(Seq("rt", "http", "client", label, "status", "500")) == Some(1))
        val name = s"svc/dog"
        assert(stats.counters.get(Seq("rt", "http", "service", name, "requests")) == Some(1))
        assert(stats.counters.get(Seq("rt", "http", "service", name, "success")) == None)
        assert(stats.counters.get(Seq("rt", "http", "service", name, "failures")) == Some(1))
        assert(stats.stats.get(Seq("rt", "http", "service", name, "retries", "per_request")) == Some(Seq(0.0)))
        assert(!stats.counters.contains(Seq("rt", "http", "service", name, "retries", "total")))
        withAnnotations { anns =>
          assert(annotationKeys(anns) == Seq("sr", "cs", "ws", "wr", "l5d.failure", "cr", "ss"))
          ()
        }
      }
    } finally {
      await(client.close())
      await(downstream.server.close())
      await(server.close())
      await(router.close())
    }
  }

  test("retries retryableIdempotent5XX") {
    retryTest("io.l5d.http.retryableIdempotent5XX", idempotentMethods)
  }

  test("retries retryablRead5XX") {
    retryTest("io.l5d.http.retryableRead5XX", readMethods)
  }

  test("retries nonRetryable5XX") {
    retryTest("io.l5d.http.nonRetryable5XX", Set.empty)
  }

  val dtabReadHeaders = Seq("l5d-dtab", "l5d-ctx-dtab")
  val dtabWriteHeader = "l5d-ctx-dtab"

  for (readHeader <- dtabReadHeaders) test(s"dtab read from $readHeader header") {
    val stats = NullStatsReceiver
    val tracer = new BufferingTracer

    @volatile var headers: HeaderMap = null

    val dog = Downstream.mk("dog") { req =>
      headers = req.headerMap
      Response()
    }
    val dtab = Dtab.read(s"""
      /svc/* => /$$/inet/127.1/${dog.port} ;
    """)

    val linker = Linker.Initializers(Seq(HttpInitializer)).load(basicConfig(dtab))
      .configured(param.Stats(stats))
      .configured(param.Tracer(tracer))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()

    val client = upstream(server)

    val req = Request()
    req.host = "dog"
    req.headerMap.set(readHeader, "/a=>/b")
    await(client(req))
    for (header <- dtabReadHeaders) {
      if (header == dtabWriteHeader) assert(headers(header) == "/a=>/b")
      else assert(!headers.contains(header))
    }
    assert(!headers.contains("dtab-local"))
  }

  test("dtab-local header is ignored") {
    val stats = NullStatsReceiver
    val tracer = new BufferingTracer

    @volatile var headers: HeaderMap = null

    val dog = Downstream.mk("dog") { req =>
      headers = req.headerMap
      Response()
    }
    val dtab = Dtab.read(s"""
      /svc/* => /$$/inet/127.1/${dog.port} ;
    """)

    val linker = Linker.Initializers(Seq(HttpInitializer)).load(basicConfig(dtab))
      .configured(param.Stats(stats))
      .configured(param.Tracer(tracer))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()

    val client = upstream(server)

    val req = Request()
    req.host = "dog"
    req.headerMap.set("dtab-local", "/a=>/b")
    await(client(req))
    assert(headers("dtab-local") == "/a=>/b")
    assert(!headers.contains(dtabWriteHeader))
  }

  test("with clearContext") {
    val downstream = Downstream.mk("dog") { req =>
      val rsp = Response()
      rsp.contentString = req.headerMap.collect {
        case (k, v) if k.startsWith("l5d-") => s"$k=$v"
      }.mkString(",")
      rsp
    }

    val localDtab = "/foo=>/bar"
    val req = Request()
    req.host = "test"
    req.headerMap("l5d-dtab") = localDtab
    req.headerMap("l5d-ctx-thing") = "yoooooo"

    val yaml =
      s"""|routers:
          |- protocol: http
          |  dtab: /svc/* => /$$/inet/127.1/${downstream.port}
          |  servers:
          |  - port: 0
          |    clearContext: true
          |""".stripMargin
    val linker = Linker.load(yaml)
    val router = linker.routers.head.initialize()
    val s = router.servers.head.serve()
    val body =
      try {
        val c = upstream(s)
        try await(c(req)).contentString
        finally await(c.close())
      } finally await(s.close())
    val headers =
      body.split(",").map { kv =>
        val Array(k, v) = kv.split("=", 2)
        k -> v
      }.toMap
    assert(headers.keySet == Set(
      "l5d-dst-service",
      "l5d-dst-client",
      "l5d-reqid",
      "l5d-ctx-trace"
    ))
  }

  test("clearContext will remove linkerd error headers and body") {
    val yaml =
      s"""|routers:
          |- protocol: http
          |  dtab: /svc/* => /$$/inet/127.1/1234
          |  servers:
          |  - port: 0
          |    clearContext: true
          |""".stripMargin
    val linker = Linker.load(yaml)
    val router = linker.routers.head.initialize()
    val s = router.servers.head.serve()

    val req = Request()
    req.host = "test"

    val c = upstream(s)
    try {
      val resp = await(c(req))
      resp.headerMap.keys must not contain ("l5d-err", "l5d-success-class", "l5d-retryable")
      resp.contentString must be("")
    } finally {
      await(c.close())
      await(s.close())
    }

  }

  test("without clearContext") {
    val downstream = Downstream.mk("dog") { req =>
      val rsp = Response()
      rsp.contentString = req.headerMap.collect {
        case (k, v) if k.startsWith("l5d-") => s"$k=$v"
      }.mkString(",")
      rsp
    }

    val localDtab = "/foo=>/bar"
    val req = Request()
    req.host = "test"
    req.headerMap("l5d-dtab") = localDtab
    req.headerMap("l5d-ctx-thing") = "yoooooo"

    val yaml =
      s"""|routers:
          |- protocol: http
          |  dtab: /svc/* => /$$/inet/127.1/${downstream.port}
          |  servers:
          |  - port: 0
          |""".stripMargin
    val linker = Linker.load(yaml)
    val router = linker.routers.head.initialize()
    val s = router.servers.head.serve()
    val body =
      try {
        val c = upstream(s)
        try await(c(req)).contentString
        finally await(c.close())
      } finally await(s.close())
    val headers =
      body.split(",").map { kv =>
        val Array(k, v) = kv.split("=", 2)
        k -> v
      }.toMap
    assert(headers.keySet == Set(
      "l5d-dst-service",
      "l5d-dst-client",
      "l5d-reqid",
      "l5d-ctx-dtab",
      "l5d-ctx-trace",
      "l5d-ctx-thing"
    ))
    assert(headers.get("l5d-ctx-dtab") == Some(localDtab))
  }
}
