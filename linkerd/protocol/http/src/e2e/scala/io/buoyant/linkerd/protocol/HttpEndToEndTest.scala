package io.buoyant.linkerd
package protocol

import com.twitter.conversions.time._
import com.twitter.finagle.{Http => FinagleHttp, Status => _, http => _, _}
import com.twitter.finagle.buoyant.linkerd.Headers
import com.twitter.finagle.http.{Method, Request, Response, Status}
import com.twitter.finagle.http.Method._
import com.twitter.finagle.stats.{InMemoryStatsReceiver, NullStatsReceiver}
import com.twitter.finagle.tracing.{Annotation, BufferingTracer, NullTracer}
import com.twitter.util._
import io.buoyant.router.Http
import io.buoyant.router.http.MethodAndHostIdentifier
import io.buoyant.test.Awaits
import java.net.InetSocketAddress
import org.scalatest.FunSuite

class HttpEndToEndTest extends FunSuite with Awaits {

  override val defaultWait = 5.seconds

  case class Downstream(name: String, server: ListeningServer) {
    val address = server.boundAddress.asInstanceOf[InetSocketAddress]
    val port = address.getPort
    val dentry = Dentry(
      Path.read(s"/s/$name"),
      NameTree.read(s"/$$/inet/127.1/$port")
    )
  }

  object Downstream {
    def mk(name: String)(f: Request=>Response): Downstream = {
      val service = Service.mk { req: Request => Future(f(req)) }
      val server = FinagleHttp.server
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
    FinagleHttp.client
      .configured(param.Stats(NullStatsReceiver))
      .configured(param.Tracer(NullTracer))
      .newClient(name, "upstream").toService
  }

  def basicConfig(dtab: Dtab) = 
    s"""|routers:
        |- protocol: http
        |  baseDtab: ${dtab.show}
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
      /http/1.1/GET/felix => /p/cat ;
      /http/1.1/GET/clifford => /p/dog ;
    """)

    val linker = Linker.Initializers(Seq(HttpInitializer)).load(basicConfig(dtab))
      .configured(param.Stats(stats))
      .configured(param.Tracer(tracer))
      .configured(Http.param.HttpIdentifier((path, dtab) => MethodAndHostIdentifier(path, true, dtab)))
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

        val path = "/http/1.1/GET/felix"
        val bound = s"/$$/inet/127.1/${cat.port}"
        withAnnotations { anns =>
          assert(annotationKeys(anns) == Seq("sr", "cs", "ws", "wr", "l5d.success", "cr", "ss"))
          assert(anns.contains(Annotation.BinaryAnnotation("namer.path", path)))
          assert(anns.contains(Annotation.BinaryAnnotation("dst.id", bound)))
          assert(anns.contains(Annotation.BinaryAnnotation("dst.path", "/")))
        }
      }

      get("clifford", "/the/big/red/dog") { rsp =>
        assert(rsp.status == Status.Ok)
        assert(rsp.contentString == "woof")

        val path = "/http/1.1/GET/clifford/the/big/red/dog"
        val bound = s"/$$/inet/127.1/${dog.port}"
        val residual = "/the/big/red/dog"
        withAnnotations { anns =>
          assert(annotationKeys(anns) == Seq("sr", "cs", "ws", "wr", "l5d.success", "cr", "ss"))
          assert(anns.contains(Annotation.BinaryAnnotation("namer.path", path)))
          assert(anns.contains(Annotation.BinaryAnnotation("dst.id", bound)))
          assert(anns.contains(Annotation.BinaryAnnotation("dst.path", residual)))
        }
      }

      get("ralph-machio") { rsp =>
        assert(rsp.status == Status.BadGateway)
        assert(rsp.headerMap.contains(Headers.Err.Key))
      }

      get("") { rsp =>
        assert(rsp.status == Status.BadRequest)
        assert(rsp.headerMap.contains(Headers.Err.Key))
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
    val dtab = Dtab.read(s"/http/1.1/GET/dog => /$label;")

    val linker = Linker.Initializers(Seq(HttpInitializer)).load(basicConfig(dtab))
      .configured(param.Stats(stats))
      .configured(param.Tracer(tracer))
      .configured(Http.param.HttpIdentifier((path, dtab) => MethodAndHostIdentifier(path, true, dtab)))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()
    val client = upstream(server)
    try {
      val okreq = Request()
      okreq.host = "dog"
      okreq.uri = "/woof"
      val okrsp = await(client(okreq))
      assert(okrsp.status == Status.Ok)
      assert(stats.counters.get(Seq("http", "srv", "127.0.0.1/0", "requests")) == Some(1))
      assert(stats.counters.get(Seq("http", "srv", "127.0.0.1/0", "success")) == Some(1))
      assert(stats.counters.get(Seq("http", "srv", "127.0.0.1/0", "failures")) == None)
      assert(stats.counters.get(Seq("http", "dst", "id", label, "requests")) == Some(1))
      assert(stats.counters.get(Seq("http", "dst", "id", label, "success")) == Some(1))
      assert(stats.counters.get(Seq("http", "dst", "id", label, "failures")) == None)

      val errreq = Request()
      errreq.host = "dog"
      val errrsp = await(client(errreq))
      assert(errrsp.status == Status.InternalServerError)
      assert(stats.counters.get(Seq("http", "srv", "127.0.0.1/0", "requests")) == Some(2))
      assert(stats.counters.get(Seq("http", "srv", "127.0.0.1/0", "success")) == Some(1))
      assert(stats.counters.get(Seq("http", "srv", "127.0.0.1/0", "failures")) == Some(1))
      assert(stats.counters.get(Seq("http", "dst", "id", label, "requests")) == Some(2))
      assert(stats.counters.get(Seq("http", "dst", "id", label, "success")) == Some(1))
      assert(stats.counters.get(Seq("http", "dst", "id", label, "failures")) == Some(1))

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
    val dtab = Dtab.read(s"/http/1.1/*/dog => /$label;")
    val yaml =
      s"""|routers:
          |- protocol: http
          |  baseDtab: ${dtab.show}
          |  responseClassifier:
          |    kind: $kind
          |  servers:
          |  - port: 0
          |""".stripMargin
    val linker = Linker.load(yaml)
      .configured(param.Stats(stats))
      .configured(param.Tracer(tracer))
      .configured(Http.param.HttpIdentifier((path, dtab) => MethodAndHostIdentifier(path, true, dtab)))
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
        assert(stats.counters.get(Seq("http", "srv", "127.0.0.1/0", "requests")) == Some(1))
        assert(stats.counters.get(Seq("http", "srv", "127.0.0.1/0", "success")) == Some(1))
        assert(stats.counters.get(Seq("http", "srv", "127.0.0.1/0", "failures")) == None)
        assert(stats.counters.get(Seq("http", "dst", "id", label, "requests")) == Some(2))
        assert(stats.counters.get(Seq("http", "dst", "id", label, "success")) == Some(1))
        assert(stats.counters.get(Seq("http", "dst", "id", label, "failures")) == Some(1))
        assert(stats.counters.get(Seq("http", "dst", "id", label, "status", "200")) == Some(1))
        assert(stats.counters.get(Seq("http", "dst", "id", label, "status", "500")) == Some(1))
        val name = s"http/1.1/$method/dog"
        assert(stats.counters.get(Seq("http", "dst", "path", name, "requests")) == Some(1))
        assert(stats.counters.get(Seq("http", "dst", "path", name, "success")) == Some(1))
        assert(stats.counters.get(Seq("http", "dst", "path", name, "failures")) == None)
        assert(stats.stats.get(Seq("http", "dst", "path", name, "retries")) == Some(Seq(1.0)))
        withAnnotations { anns =>
          assert(annotationKeys(anns) == Seq("sr", "cs", "ws", "wr", "l5d.retryable", "cr", "cs", "ws", "wr", "l5d.success", "cr", "ss"))
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
        assert(stats.counters.get(Seq("http", "srv", "127.0.0.1/0", "requests")) == Some(1))
        assert(stats.counters.get(Seq("http", "srv", "127.0.0.1/0", "success")) == None)
        assert(stats.counters.get(Seq("http", "srv", "127.0.0.1/0", "failures")) == Some(1))
        assert(stats.counters.get(Seq("http", "dst", "id", label, "requests")) == Some(1))
        assert(stats.counters.get(Seq("http", "dst", "id", label, "success")) == None)
        assert(stats.counters.get(Seq("http", "dst", "id", label, "failures")) == Some(1))
        assert(stats.counters.get(Seq("http", "dst", "id", label, "status", "200")) == None)
        assert(stats.counters.get(Seq("http", "dst", "id", label, "status", "500")) == Some(1))
        val name = s"http/1.1/$method/dog"
        assert(stats.counters.get(Seq("http", "dst", "path", name, "requests")) == Some(1))
        assert(stats.counters.get(Seq("http", "dst", "path", name, "success")) == None)
        assert(stats.counters.get(Seq("http", "dst", "path", name, "failures")) == Some(1))
        assert(stats.stats.get(Seq("http", "dst", "path", name, "retries")) == Some(Seq(0.0)))
        withAnnotations { anns =>
          assert(annotationKeys(anns) == Seq("sr", "cs", "ws", "wr", "l5d.failure", "cr", "ss"))
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
    retryTest("io.l5d.retryableIdempotent5XX", idempotentMethods)
  }

  test("retries retryablRead5XX") {
    retryTest("io.l5d.retryableRead5XX", readMethods)
  }

  test("retries nonRetryable5XX") {
    retryTest("io.l5d.nonRetryable5XX", Set.empty)
  }
}
