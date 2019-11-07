package io.buoyant.linkerd
package protocol

import com.twitter.concurrent.AsyncStream
import com.twitter.conversions.DurationOps._
import com.twitter.finagle.{Http => FinagleHttp, Status => _, http => _, _}
import com.twitter.finagle.buoyant.linkerd.Headers
import com.twitter.finagle.http.Method._
import com.twitter.finagle.http.filter.{ClientDtabContextFilter, ServerDtabContextFilter}
import com.twitter.finagle.http.{param => _, _}
import com.twitter.finagle.service.ExpiringService
import com.twitter.finagle.stats.{InMemoryStatsReceiver, NullStatsReceiver}
import com.twitter.finagle.tracing.{Annotation, BufferingTracer, NullTracer}
import com.twitter.io.{Buf, Pipe}
import com.twitter.util._
import io.buoyant.router.StackRouter.Client.PerClientParams
import io.buoyant.test.{Awaits, BudgetedRetries}
import java.io.File
import java.net.InetSocketAddress
import org.scalatest.{FunSuite, MustMatchers, OptionValues}
import org.scalatest.tagobjects.Retryable
import org.scalatest.time.{Millis, Seconds, Span}
import scala.io.Source
import scala.util.Random

class HttpEndToEndTest
  extends FunSuite
    with Awaits
    with MustMatchers
    with OptionValues
    with BudgetedRetries {


  case class Downstream(name: String, server: ListeningServer) {
    val address = server.boundAddress.asInstanceOf[InetSocketAddress]
    val port = address.getPort
    val dentry = Dentry(
      Path.read(s"/svc/$name"),
      NameTree.read(s"/$$/inet/127.1/$port")
    )
  }

  object Downstream {
    def mk(name: String)(f: Request=>Response): Downstream = {
      val service = Service.mk { req: Request => Future(f(req)) }
      val stack = FinagleHttp.server.stack
        .remove(Headers.Ctx.serverModule.role)
        .remove(ServerDtabContextFilter.role)
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
    val stack = FinagleHttp.client.stack
      .remove(Headers.Ctx.clientModule.role)
      .remove(ClientDtabContextFilter.role)
    FinagleHttp.client.withStack(stack)
      .configured(param.Stats(NullStatsReceiver))
      .configured(param.Tracer(NullTracer))
      .newClient(name, "upstream").toService
  }

  def basicConfig(dtab: Dtab, streaming: Boolean = false) =
    s"""|routers:
        |- protocol: http
        |  streamingEnabled: $streaming
        |  dtab: ${dtab.show}
        |  servers:
        |  - port: 0
        |""".stripMargin

  def annotationKeys(annotations: Seq[Annotation]): Seq[String] =
    annotations.collect {
      case Annotation.ClientSend => "cs"
      case Annotation.ClientRecv => "cr"
      case Annotation.ServerSend => "ss"
      case Annotation.ServerRecv => "sr"
      case Annotation.WireSend => "ws"
      case Annotation.WireRecv => "wr"
      case Annotation.BinaryAnnotation(k, _) if k == "l5d.success" => k
      case Annotation.Message(m) if Seq("l5d.retryable", "l5d.failure").contains(m) => m
    }

  test("linking", Retryable) {
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
          assert(annotationKeys(anns) == Seq("wr", "sr", "cs", "ws", "wr", "l5d.success", "cr", "ss", "ws"))
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


  test("marks 5XX as failure by default", Retryable) {
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

  test("marks exceptions as failure by default", Retryable) {
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
      assert(stats.counters.get(Seq("rt", "http", "server", "127.0.0.1/0", "success")).forall(_ == 0))
      assert(stats.counters.get(Seq("rt", "http", "server", "127.0.0.1/0", "failures")) == Some(1))
      assert(stats.counters.get(Seq("rt", "http", "service", "svc/dog", "requests")) == Some(1))
      assert(stats.counters.get(Seq("rt", "http", "service", "svc/dog", "success")).forall(_ == 0))
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
          assert(annotationKeys(anns) == Seq("wr", "sr", "cs", "ws", "wr", "l5d.retryable", "cr", "cs", "ws", "wr", "l5d.success", "cr", "ss", "ws"))
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
        assert(stats.counters.get(Seq("rt", "http", "server", "127.0.0.1/0", "success")).forall(_ == 0))
        assert(stats.counters.get(Seq("rt", "http", "server", "127.0.0.1/0", "failures")) == Some(1))
        assert(stats.counters.get(Seq("rt", "http", "client", label, "requests")) == Some(1))
        assert(stats.counters.get(Seq("rt", "http", "client", label, "success")).forall(_ == 0))
        assert(stats.counters.get(Seq("rt", "http", "client", label, "failures")) == Some(1))
        assert(stats.counters.get(Seq("rt", "http", "client", label, "status", "200")).forall(_ == 0))
        assert(stats.counters.get(Seq("rt", "http", "client", label, "status", "500")) == Some(1))
        val name = s"svc/dog"
        assert(stats.counters.get(Seq("rt", "http", "service", name, "requests")) == Some(1))
        assert(stats.counters.get(Seq("rt", "http", "service", name, "success")).forall(_ == 0))
        assert(stats.counters.get(Seq("rt", "http", "service", name, "failures")) == Some(1))
        assert(stats.stats.get(Seq("rt", "http", "service", name, "retries", "per_request")) == Some(Seq(0.0)))
        assert(stats.counters.get(Seq("rt", "http", "service", name, "retries", "total")).forall(_ == 0))
        withAnnotations { anns =>
          assert(annotationKeys(anns) == Seq("wr", "sr", "cs", "ws", "wr", "l5d.failure", "cr", "ss", "ws"))
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

  test("retries retryableIdempotent5XX", Retryable) {
    retryTest("io.l5d.http.retryableIdempotent5XX", idempotentMethods)
  }

  test("retries retryablRead5XX", Retryable) {
    retryTest("io.l5d.http.retryableRead5XX", readMethods)
  }

  test("retries nonRetryable5XX", Retryable) {
    retryTest("io.l5d.http.nonRetryable5XX", Set.empty)
  }

  val dtabReadHeaders = Seq("l5d-dtab", "l5d-ctx-dtab")
  val dtabWriteHeader = "l5d-ctx-dtab"

  for (readHeader <- dtabReadHeaders) test(s"dtab read from $readHeader header", Retryable) {
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

  test("dtab-local header is ignored", Retryable) {
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

  test("with clearContext", Retryable) {
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

  test("clearContext will remove linkerd error headers and body", Retryable) {
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

  test("clearContext will remove linkerd error headers and body when identifier is set", Retryable) {
    val yaml =
      s"""|routers:
          |- protocol: http
          |  dtab: /svc/a/b => /$$/inet/127.1/1234
          |  identifier:
          |    kind: io.l5d.path
          |    segments: 2
          |  servers:
          |  - port: 0
          |    clearContext: true
          |""".stripMargin
    val linker = Linker.load(yaml)
    val router = linker.routers.head.initialize()
    val s = router.servers.head.serve()

    val req = Request("/a")
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

  test("timestampHeader adds header", Retryable) {
    @volatile var headers: Option[HeaderMap] = None
    val downstream = Downstream.mk("test") {
      req =>
        headers = Some(req.headerMap)
        val rsp = Response()
        rsp.status = Status.Ok
        rsp
    }

    val yaml =
      s"""|routers:
          |- protocol: http
          |  dtab: ${downstream.dentry.show};
          |  servers:
          |  - port: 0
          |    timestampHeader: x-request-start
          |""".stripMargin
    val linker = Linker.load(yaml)
    val router = linker.routers.head.initialize()
    val s = router.servers.head.serve()

    val req = Request()
    req.host = "test"

    val c = upstream(s)
    try {
      val resp = await(c(req))

      resp.status must be (Status.Ok)
      headers.value.keys must contain ("x-request-start")
      Try(headers.value.get("x-request-start").value.toLong) must be a 'return
    } finally {
      await(c.close())
      await(downstream.server.close())
      await(s.close())
    }
  }

  test("no timestampHeader does not add timestamp header", Retryable) {
    @volatile var headers: Option[HeaderMap] = None
    val downstream = Downstream.mk("test") {
      req =>
        headers = Some(req.headerMap)
        val rsp = Response()
        rsp.status = Status.Ok
        rsp
    }

    val yaml =
      s"""|routers:
          |- protocol: http
          |  dtab: ${downstream.dentry.show};
          |  servers:
          |  - port: 0
          |""".stripMargin
    val linker = Linker.load(yaml)
    val router = linker.routers.head.initialize()
    val s = router.servers.head.serve()

    val req = Request()
    req.host = "test"

    val c = upstream(s)
    try {
      val resp = await(c(req))

      resp.status must be (Status.Ok)
      headers.value.keys must not contain "x-request-start"
    } finally {
      await(c.close())
      await(downstream.server.close())
      await(s.close())
    }
  }

  test("without clearContext", Retryable) {
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

  test("logs to correct files", Retryable) {
    val downstream = Downstream.mk("test") {
      req =>
        val rsp = Response()
        rsp.status = Status.Ok
        rsp
    }

    val logs = Array(
      File.createTempFile("access", "log0"),
      File.createTempFile("access", "log1")
    )
    logs.foreach { log => log.deleteOnExit() }

    val rand = new Random()

    def randomPort = 32000 + (Random.nextDouble * 30000).toInt

    val yaml =
      s"""|routers:
          |- protocol: http
          |  label: router0
          |  httpAccessLog: ${logs(0).getPath}
          |  dtab: ${downstream.dentry.show};
          |  servers:
          |  - port: ${randomPort}
          |- protocol: http
          |  label: router1
          |  httpAccessLog: ${logs(1).getPath}
          |  dtab: ${downstream.dentry.show};
          |  servers:
          |  - port: ${randomPort}
          |""".stripMargin

    val routers = Linker.load(yaml).routers.map { router =>
      router.initialize()
    }

    try {
      Array("/path0", "/path1", "/path2", "/path3").zipWithIndex.foreach {
        case (path, i) =>
          val routerIndex = i%2

          val req = Request()
          req.host = "test"
          req.uri = path

          val s = routers(routerIndex).servers.head.serve()
          val c = upstream(s)
          try {
            val resp = await(c(req))
            resp.status must be (Status.Ok)
          } finally {
            await(c.close())
            await(s.close())
          }

          val source = Source.fromFile(logs(routerIndex))
          val lines = try source.mkString finally source.close()
          assert(lines.contains(path))
      }
    } finally {
      await(downstream.server.close())
      routers.foreach { router => await(router.close()) }
    }
  }

  test("clientSession idleTimeMs should close client connections") {
    val config =
      s"""|routers:
          |- protocol: http
          |  experimental: true
          |  dtab: |
          |    /p/fox => /$$/inet/127.1/{fox.port} ;
          |    /svc/century => /p/fox ;
          |  servers:
          |  - port: 0
          |  client:
          |    clientSession:
          |      idleTimeMs: 1500
          |""".stripMargin

    idleTimeMsBaseTest(config){ (router:Router.Initialized, stats:InMemoryStatsReceiver, foxPort:Int) =>

      // Assert
      def activeConnectionsCount = stats.gauges(Seq("rt", "http", "client", s"$$/inet/127.1/${foxPort}", "connections"))

      // An incoming request through the Http.Router will establish an active connection; We expect to see it here
      assert(activeConnectionsCount() == 1.0)

      eventually(timeout(Span(5, Seconds)), interval(Span(250, Millis))) {
        val cnt = activeConnectionsCount()
        assert(cnt == 0.0)
      }

      val clientSessionParams = router.params[PerClientParams].paramsFor(Path.read("/svc/century"))[ExpiringService.Param]
      assert(clientSessionParams.idleTime == 1500.milliseconds)
      assert(clientSessionParams.lifeTime == Duration.Top)

      ()
    }
  }

  test("clientSession idleTimeMs should close client connections for static client") {
    val config =
      s"""|routers:
          |- protocol: http
          |  experimental: true
          |  dtab: |
          |    /p/fox => /$$/inet/127.1/{fox.port} ;
          |    /svc/century => /p/fox ;
          |  servers:
          |  - port: 0
          |  client:
          |    kind: io.l5d.static
          |    configs:
          |      - prefix: /*
          |        clientSession:
          |          idleTimeMs: 1500
          |""".stripMargin

    idleTimeMsBaseTest(config){ (router:Router.Initialized, stats:InMemoryStatsReceiver, foxPort:Int) =>

      // Assert
      def activeConnectionsCount = stats.gauges(Seq("rt", "http", "client", s"$$/inet/127.1/${foxPort}", "connections"))

      // An incoming request through the Http.Router will establish an active connection; We expect to see it here
      assert(activeConnectionsCount() == 1.0)

      eventually(timeout(Span(5, Seconds)), interval(Span(250, Millis))) {
        val cnt = activeConnectionsCount()
        assert(cnt == 0.0)
      }

      val clientSessionParams = router.params[PerClientParams].paramsFor(Path.read("/svc/century"))[ExpiringService.Param]
      assert(clientSessionParams.idleTime == 1500.milliseconds)
      assert(clientSessionParams.lifeTime == Duration.Top)

      ()
    }
  }

  test("clientSession idleTimeMs should not close client connections when isn't specified") {
    val config =
      s"""|routers:
          |- protocol: http
          |  experimental: true
          |  dtab: |
          |    /p/fox => /$$/inet/127.1/{fox.port} ;
          |    /svc/century => /p/fox ;
          |  servers:
          |  - port: 0
          |  client:
          |    forwardClientCert: false
          |""".stripMargin

    idleTimeMsBaseTest(config){ (router:Router.Initialized, stats:InMemoryStatsReceiver, foxPort:Int) =>

      // Assert
      def activeConnectionsCount = stats.gauges(Seq("rt", "http", "client", s"$$/inet/127.1/${foxPort}", "connections"))

      // An incoming request through the Http.Router will establish an active connection; We expect to see it here
      assert(activeConnectionsCount() == 1.0)

      val clientSessionParams = router.params[PerClientParams].paramsFor(Path.read("/svc/century"))[ExpiringService.Param]
      assert(clientSessionParams.idleTime == Duration.Top)
      assert(clientSessionParams.lifeTime == Duration.Top)

      ()
    }
  }

  test("serverSession idleTimeMs should close server connections") {
    val config =
      s"""|routers:
          |- protocol: http
          |  experimental: true
          |  dtab: |
          |    /p/fox => /$$/inet/127.1/{fox.port} ;
          |    /svc/century => /p/fox ;
          |  servers:
          |  - port: 0
          |    serverSession:
          |      idleTimeMs: 1500
          |      lifeTimeMs: 3000
          |""".stripMargin

    serverIdleTimeMsBaseTest(config){ (router:Router.Initialized, stats:InMemoryStatsReceiver) =>
      // Assert
      def connectsCount = stats.counters(Seq("rt", "http", "server", "127.0.0.1/0", "connects"))
      def activeConnectionsCount = stats.gauges(Seq("rt", "http", "server", "127.0.0.1/0", "connections"))
      def idleConnectionsCount = () => stats.counters(Seq("rt", "http", "server", "127.0.0.1/0", "idle"))

      // An incoming request through the Http.Router will establish an active connection; We expect to see it here
      assert(connectsCount == 1.0)
      assert(activeConnectionsCount() == 1.0)

      eventually(timeout(Span(5, Seconds)), interval(Span(250, Millis))) {
        assert(activeConnectionsCount() == 0.0)
        assert(idleConnectionsCount() == 1.0)
      }

      ()
    }
  }

  test("serverSession lifeTimeMs should close server connections") {
    val config =
      s"""|routers:
          |- protocol: http
          |  experimental: true
          |  dtab: |
          |    /p/fox => /$$/inet/127.1/{fox.port} ;
          |    /svc/century => /p/fox ;
          |  servers:
          |  - port: 0
          |    serverSession:
          |      idleTimeMs: 3000
          |      lifeTimeMs: 1500
          |""".stripMargin

    serverIdleTimeMsBaseTest(config){ (router:Router.Initialized, stats:InMemoryStatsReceiver) =>
      // Assert
      def connectsCount = stats.counters(Seq("rt", "http", "server", "127.0.0.1/0", "connects"))
      def activeConnectionsCount = stats.gauges(Seq("rt", "http", "server", "127.0.0.1/0", "connections"))
      def lifetimeConnectionsCount = () => stats.counters(Seq("rt", "http", "server", "127.0.0.1/0", "lifetime"))

      // An incoming request through the Http.Router will establish an active connection; We expect to see it here
      assert(connectsCount == 1.0)
      assert(activeConnectionsCount() == 1.0)

      eventually(timeout(Span(5, Seconds)), interval(Span(250, Millis))) {
        assert(activeConnectionsCount() == 0.0)
        assert(lifetimeConnectionsCount() == 1.0)
      }

      ()
    }
  }

  test("serverSession lifeTimeMs should not close server connections if not specified") {
    val config =
      s"""|routers:
          |- protocol: http
          |  experimental: true
          |  dtab: |
          |    /p/fox => /$$/inet/127.1/{fox.port} ;
          |    /svc/century => /p/fox ;
          |  servers:
          |  - port: 0
          |""".stripMargin

    serverIdleTimeMsBaseTest(config){ (router:Router.Initialized, stats:InMemoryStatsReceiver) =>
      // Assert
      def connectsCount = stats.counters(Seq("rt", "http", "server", "127.0.0.1/0", "connects"))
      def activeConnectionsCount = stats.gauges(Seq("rt", "http", "server", "127.0.0.1/0", "connections"))

      // An incoming request through the Http.Router will establish an active connection; We expect to see it here
      assert(connectsCount == 1.0)
      assert(activeConnectionsCount() == 1.0)

      assert(router.servers.head.params[ExpiringService.Param].idleTime == Duration.Top)
      assert(router.servers.head.params[ExpiringService.Param].lifeTime == Duration.Top)

      ()
    }
  }


  test("requests with Max-Forwards header, l5d-add-context and method TRACE are sent downstream") {
    @volatile var headers: HeaderMap = null
    @volatile var method: Method = null
    val downstream = Downstream.mk("dog") { req =>
      headers = req.headerMap
      method = req.method
      val resp = Response()
      resp.contentString = "response from downstream"
      resp
    }
    val dtab = Dtab.read(s"""
      /svc/* => /$$/inet/127.1/${downstream.port} ;
    """)

    val linker = Linker.Initializers(Seq(HttpInitializer)).load(basicConfig(dtab))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()
    val client = upstream(server)

    val req = Request()
    req.host = "dog"
    req.headerMap.add("Max-Forwards", "5")
    req.headerMap.add("l5d-add-context", "true")
    req.method = Method.Trace
    val resp = await(client(req))
    assert(resp.contentString.contains("response from downstream"))
    assert(headers.contains("Max-Forwards"))
    assert(headers.contains("l5d-add-context"))
    assert(method == Method.Trace)

  }

  test("prints out human readable dtab resolution path"){
    val downstream = Downstream.mk("dog") { req =>
      Response()
    }

    val dtab = Dtab.read(s"""
      /srv => /$$/inet/127.1/${downstream.port};
      /svc => /srv;
    """)

    val linker = Linker.Initializers(Seq(HttpInitializer)).load(basicConfig(dtab))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()
    val client = upstream(server)

    val req = Request()
    req.host = "dog"
    req.method = Method.Trace
    req.headerMap.add("Max-Forwards", "5")
    req.headerMap.add("l5d-add-context", "true")
    val resp = await(client(req))
    assert(resp.contentString.contains(
      s"""|client name: /$$/inet/127.1/${downstream.port}
          |addresses: [127.0.0.1:${downstream.port}]
          |selected address: 127.0.0.1:${downstream.port}
          |dtab resolution:
          |  /svc/dog
          |  /srv/dog (/svc=>/srv)
          |  /$$/inet/127.1/${downstream.port}/dog (/srv=>/$$/inet/127.1/${downstream.port})
          |""".stripMargin))
  }

  test("discards content from chunked server response during diagnostic trace"){
    val responseDiscardedMsg = "Diagnostic trace encountered chunked response. Response content discarded."
    val downstream = Downstream.mk("dog") { req =>
      val chunkedWriter = new Pipe[Buf]()
      AsyncStream[Buf](
        Seq("Chunked", "Response")
        .map(Buf.Utf8(_)): _*)
        .foreachF(chunkedWriter.write)
        .before(chunkedWriter.close())
     Response(req.version, Status.Ok, chunkedWriter)
    }

    val dtab = Dtab.read(s"""
      /srv => /$$/inet/127.1/${downstream.port};
      /svc => /srv;
    """)

    val linker = Linker.Initializers(Seq(HttpInitializer)).load(basicConfig(dtab, streaming = true))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()
    val client = upstream(server)

    val req = Request()
    req.host = "dog"
    req.method = Method.Trace
    req.headerMap.add("Max-Forwards", "5")
    req.headerMap.add("l5d-add-context", "true")
    val resp = await(client(req))

    assert(resp.contentString.contains(responseDiscardedMsg))
  }



  test("returns 400 for requests that have more than allowed hops", Retryable) {
    val yaml =
      s"""|routers:
          |- protocol: http
          |  servers:
          |  - port: 0
          |    maxCallDepth: 2
          |""".stripMargin
    val linker = Linker.load(yaml)
    val router = linker.routers.head.initialize()
    val s = router.servers.head.serve()

    val req = Request()
    req.headerMap.add(Fields.Via, "hop1, hop2, hop3")

    val c = upstream(s)
    try {
      val resp = await(c(req))
      resp.status must be (Status.BadRequest)
      resp.contentString must be ("Maximum number of calls (2) has been exceeded. Please check for proxy loops.")

    } finally {
      await(c.close())
      await(s.close())
    }
  }


  def idleTimeMsBaseTest(config:String)(assertionsF: (Router.Initialized, InMemoryStatsReceiver, Int) => Unit): Unit = {
    // Arrange
    val stats = new InMemoryStatsReceiver
    val fox = Downstream.const("fox", "what does the fox say?")

    val configWithPort = config.replace("{fox.port}", fox.port.toString)

    val linker = Linker.Initializers(Seq(HttpInitializer)).load(configWithPort)
      .configured(param.Stats(stats))

    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()

    val client = upstream(server)

    def get(host: String, path: String = "/")(f: Response => Unit): Unit = {
      val req = Request(path)
      req.host = host
      val rsp = await(client(req))
      f(rsp)
    }

    // Act
    try {
      get("century") { rsp =>
        assert(rsp.status == Status.Ok)
        assert(rsp.contentString == "what does the fox say?")
        ()
      }

      // Assert
      assertionsF(router, stats, fox.port)

    } finally {
      await(client.close())
      await(fox.server.close())
      await(server.close())
      await(router.close())
    }
  }

  def serverIdleTimeMsBaseTest(config:String)(assertionsF: (Router.Initialized, InMemoryStatsReceiver) => Unit): Unit = {
    // Arrange
    val stats = new InMemoryStatsReceiver
    val fox = Downstream.const("fox", "what does the fox say?")

    val configWithPort = config.replace("{fox.port}", fox.port.toString)

    val linker = Linker.Initializers(Seq(HttpInitializer)).load(configWithPort)
      .configured(param.Stats(stats))

    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()

    val client = upstream(server)

    def get(host: String, path: String = "/")(f: Response => Unit): Unit = {
      val req = Request(path)
      req.host = host
      val rsp = await(client(req))
      f(rsp)
    }

    // Act
    try {
      get("century") { rsp =>
        assert(rsp.status == Status.Ok)
        assert(rsp.contentString == "what does the fox say?")
        ()
      }

      // Assert
      assertionsF(router, stats)

    } finally {
      await(client.close())
      await(fox.server.close())
      await(server.close())
      await(router.close())
    }


  }
}
