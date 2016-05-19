package io.buoyant.router

import com.twitter.conversions.time._
import com.twitter.finagle.service.Retries
import com.twitter.finagle.stack.nilStack
import com.twitter.finagle.http.{Request, Response, Status, Version}
import com.twitter.finagle.stats.{NullStatsReceiver, InMemoryStatsReceiver}
import com.twitter.finagle.tracing.{Annotation, BufferingTracer, NullTracer}
import com.twitter.finagle.{Http => FinagleHttp, Status => _, http => _, _}
import com.twitter.util._
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
    def factory(name: String)(f: ClientConnection => Service[Request, Response]): Downstream = {
      val factory = new ServiceFactory[Request, Response] {
        def apply(conn: ClientConnection): Future[Service[Request, Response]] = Future(f(conn))
        def close(deadline: Time): Future[Unit] = Future.Done
      }
      val server = FinagleHttp.server
        .configured(param.Label(name))
        .configured(param.Tracer(NullTracer))
        .serve(":*", factory)
      Downstream(name, server)
    }

    def mk(name: String)(f: Request=>Response): Downstream =
      factory(name) { _ =>
        Service.mk[Request, Response] { req =>
          Future(f(req))
        }
      }

    def const(name: String, value: String): Downstream =
      mk(name) { _ =>
        val rsp = Response()
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

  test("end-to-end routing") {
    val stats = NullStatsReceiver
    val tracer = new BufferingTracer
    def withAnnotations(f: Seq[Annotation] => Unit): Unit = {
      f(tracer.iterator.map(_.annotation).toSeq)
      tracer.clear()
    }

    val cat = Downstream.const("cat", "meow")
    val dog = Downstream.const("dog", "woof")
    val router = {
      val dtab = Dtab.read(s"""
        /p/cat => /$$/inet/127.1/${cat.port} ;
        /p/dog => /$$/inet/127.1/${dog.port} ;

        /http/1.1/GET/felix => /p/cat ;
        /http/1.1/GET/clifford => /p/dog ;
      """)

      val factory = Http.router
        .configured(RoutingFactory.BaseDtab(() => dtab))
        .configured(RoutingFactory.DstPrefix(Path.Utf8("http")))
        .configured(Http.param.HttpIdentifier((path, dtab) => http.MethodAndHostIdentifier(path, true, dtab)))
        .factory()

      Http.server
        .serve(new InetSocketAddress(0), factory)
    }


    val client = upstream(router)
    def get(host: String, path: String = "/")(f: Response => Unit): Unit = {
      val req = Request()
      req.host = host
      req.uri = path
      val rsp = await(client(req))
      f(rsp)
    }

    try {
      println("routing successfully")
      get("felix") { rsp =>
        assert(rsp.status == Status.Ok)
        assert(rsp.contentString == "meow")
      }

      println("routing successfully")
      get("clifford", "/the/big/red/dog") { rsp =>
        assert(rsp.status == Status.Ok)
        assert(rsp.contentString == "woof")
      }

      // todo check stats
      // todo check tracer
      //tracer.clear()
    } finally {
      await(client.close())
      await(cat.server.close())
      await(dog.server.close())
      await(router.close())
    }
  }

  test("strips connection header") {
    @volatile var connection: Option[Option[String]] = None
    val srv = Downstream.mk("srv") { req =>
      connection = Some(req.headerMap.get("Connection"))
      Response()
    }

    val router = {
      val dtab = Dtab.read(s"/http/1.1 => /$$/inet/127.1/${srv.port};")
      val factory = Http.router
        .configured(RoutingFactory.BaseDtab(() => dtab))
        .configured(RoutingFactory.DstPrefix(Path.Utf8("http")))
        .factory()
      Http.serve(new InetSocketAddress(0), factory)
    }
    val client = upstream(router)

    try {
      println("ensuring connection header stripped")
      val req = Request()
      req.host = "host"
      req.headerMap.set("Connection", "close")
      val _ = await(client(req))
      assert(connection == Some(None))

    } finally {
      await(client.close())
      await(srv.server.close())
      await(router.close())
    }
  }

  test("http/1.1: server closes connection after response") {
    @volatile var connection: Option[ClientConnection] = None
    val downstream = Downstream.factory("ds") { conn =>
      connection = Some(conn)
      Service.mk[Request, Response](_ => Future.value(Response()))
    }

    val stats = new InMemoryStatsReceiver
    def downstreamCounter(name: String) = {
      val k = Seq("http", "dst", "id", s"$$/inet/127.1/${downstream.port}", name)
      stats.counters.get(k)
    }

    @volatile var retriesToDo = 0
    @volatile var err: Option[Throwable] = None
    val router = {
      val dtab = Dtab.read(s"/http/1.1/*/ds => /$$/inet/127.1/${downstream.port}")
      def doReq(req: () => Future[Response], retries: Int = 0): Future[Response] =
        req().transform { ret =>
          if (retries > 0) {
            doReq(req, retries - 1)
          } else Future.const(ret)
        }
      val retryFilter = Filter.mk[Request, Response, Request, Response] { (req, svc) =>
        doReq(() => svc(req), retriesToDo)
      }
      val errFilter = Filter.mk[Request, Response, Request, Response] { (req, svc) =>
        svc(req).onFailure { e =>
          err = Some(e)
        }
      }
      val factory = Http.router
        .withPathStack(Http.router.pathStack
          .replace(ClassifiedRetries.role, retryFilter)
          .insertBefore(ClassifiedRetries.role, errFilter))
        .configured(RoutingFactory.BaseDtab(() => dtab))
        .configured(RoutingFactory.DstPrefix(Path.Utf8("http")))
        .configured(param.Stats(stats))
        .factory()
      Http.serve(new InetSocketAddress(0), factory)
    }

    val client = upstream(router)
    def get() = {
      val req = Request()
      req.host = "ds"
      req.version = Version.Http11
      await(client(req))
    }

    // Issue a request
    try {
      println("sending an http/1.1 request")
      val rsp0 = get()
      // don't disconnect to prove we reuse the connection
      assert(err == None)
      assert(rsp0.status == Status.Ok)
      //assert(downstreamCounter("connects") == Some(1))
      //assert(downstreamCounter("closed") == None)

      println("sending another http/1.1 request")
      val rsp1 = get()
      // disconnect, to show that linkerd handles the following request gracefully
      println("dropping the serverside connection")
      assert(connection.isDefined)
      connection.foreach(c => await(c.close()))
      connection = None
      assert(err == None)
      assert(rsp1.status == Status.Ok)
      //assert(downstreamCounter("connects") == Some(1))

      println("sending an http/1.1 request with a retry")
      retriesToDo = 1
      val rsp2 = get()
      println("dropping the serverside connection")
      assert(connection.isDefined)
      connection.foreach(c => await(c.close()))
      connection = None
      assert(err == None)
      assert(rsp2.status == Status.Ok)
      //assert(downstreamCounter("connects") == Some(2))

    } finally {
      await(client.close())
      await(router.close())
      await(downstream.server.close())
    }
  }

  test("http/1.0: server closes connection after response") {
    val downstream = Downstream.mk("ds") { req =>
      val status = req.version match {
        case Version.Http11 => Status.HttpVersionNotSupported
        case Version.Http10 => Status.Ok
      }
      Response(Version.Http10, status)
    }

    val stats = new InMemoryStatsReceiver
    def downstreamCounter(name: String) = {
      val k = Seq("http", "dst", "id", s"$$/inet/127.1/${downstream.port}", name)
      stats.counters.get(k)
    }

    @volatile var retriesToDo = 0
    @volatile var err: Option[Throwable] = None
    val router = {
      val dtab = Dtab.read(s"/http => /$$/inet/127.1/${downstream.port}")
      def doReq(req: () => Future[Response], retries: Int = 0): Future[Response] =
        req().transform { ret =>
          if (retries > 0) {
            doReq(req, retries - 1)
          } else Future.const(ret)
        }
      val retryFilter = Filter.mk[Request, Response, Request, Response] { (req, svc) =>
        doReq(() => svc(req), retriesToDo)
      }
      val errFilter = Filter.mk[Request, Response, Request, Response] { (req, svc) =>
        svc(req).onFailure { e =>
          err = Some(e)
        }
      }
      val factory = Http.router
        .withPathStack(Http.router.pathStack
          .replace(ClassifiedRetries.role, retryFilter)
          .insertBefore(ClassifiedRetries.role, errFilter))
        .configured(RoutingFactory.BaseDtab(() => dtab))
        .configured(RoutingFactory.DstPrefix(Path.Utf8("http")))
        .configured(param.Stats(stats))
        .factory()
      Http.serve(new InetSocketAddress(0), factory)
    }

    val client = upstream(router)
    def get() = {
      val req = Request()
      req.host = "ds"
      req.version = Version.Http10
      await(client(req))
    }

    // Issue a request
    try {
      println("sending an http/1.0 request")
      val rsp0 = get()
      assert(err == None)
      assert(rsp0.status == Status.Ok)
      //assert(downstreamCounter("connects") == Some(1))

      println("sending another http/1.0 request")
      val rsp1 = get()
      assert(err == None)
      assert(rsp1.status == Status.Ok)
      //assert(downstreamCounter("connects") == Some(2))

      println("sending an http/1.0 request with retries")
      retriesToDo = 1
      val rsp2 = get()
      assert(err == None)
      assert(rsp2.status == Status.Ok)
      //assert(downstreamCounter("connects") == Some(4))

    } finally {
      await(client.close())
      await(router.close())
      await(downstream.server.close())
    }
  }
}
