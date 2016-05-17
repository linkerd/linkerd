package io.buoyant.router

import com.twitter.conversions.time._
import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.{Annotation, BufferingTracer, NullTracer}
import com.twitter.finagle.{Http => FinagleHttp, Status => _, http => _, _}
import com.twitter.util._
import io.buoyant.test.Awaits
import java.net.InetSocketAddress
import org.scalatest.FunSuite

class HttpEndToEndTest extends FunSuite with Awaits {

  override val defaultWait = 2.seconds

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
      get("felix") { rsp =>
        assert(rsp.status == Status.Ok)
        assert(rsp.contentString == "meow")
      }

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
}
