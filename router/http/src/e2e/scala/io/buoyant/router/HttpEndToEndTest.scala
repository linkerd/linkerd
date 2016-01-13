package io.buoyant.router

import com.twitter.common.metrics.Metrics
import com.twitter.conversions.time._
import com.twitter.finagle.{Http => FinagleHttp, Status=>_, http=>_, _}
import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.finagle.stats.{ImmediateMetricsStatsReceiver, NullStatsReceiver}
import com.twitter.finagle.tracing.{Annotation, BufferingTracer, Trace, NullTracer}
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
    val address = server.boundAddress.asInstanceOf[InetSocketAddress]
    val name = Name.Bound(Var.value(Addr.Bound(address)), address)
    FinagleHttp.client
      .configured(param.Stats(NullStatsReceiver))
      .configured(param.Tracer(NullTracer))
      .newClient(name, "upstream").toService
  }

  test("end-to-end echo routing") {
    val metrics = Metrics.createDetached()
    val stats = new ImmediateMetricsStatsReceiver(metrics)
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
        .configured(Http.param.UriInDst(true))
        .configured(param.Stats(stats))
        .configured(param.Tracer(tracer))
        .factory()

      Http.server
        .configured(param.Stats(stats))
        .configured(param.Tracer(tracer))
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

        val path = "/http/1.1/GET/felix"
        val bound = s"/$$/inet/127.1/${cat.port}"
        assert(rsp.headerMap.get(http.Headers.Dst) == Some(path))
        assert(rsp.headerMap.get(http.Headers.Bound) == Some(bound))
        assert(rsp.headerMap.get(http.Headers.Residual) == None)
        withAnnotations { anns =>
          assert(anns.exists(_ == Annotation.BinaryAnnotation("namer.path", path)))
          assert(anns.exists(_ == Annotation.BinaryAnnotation("dst.id", bound)))
          assert(anns.exists(_ == Annotation.BinaryAnnotation("dst.path", "/")))
        }
      }

      get("clifford", "/the/big/red/dog") { rsp =>
        assert(rsp.status == Status.Ok)
        assert(rsp.contentString == "woof")

        val path = "/http/1.1/GET/clifford/the/big/red/dog"
        val bound = s"/$$/inet/127.1/${dog.port}"
        val residual = "/the/big/red/dog"
        assert(rsp.headerMap.get(http.Headers.Dst) == Some(path))
        assert(rsp.headerMap.get(http.Headers.Bound) == Some(bound))
        assert(rsp.headerMap.get(http.Headers.Residual) == Some(residual))
        withAnnotations { anns =>
          assert(anns.exists(_ == Annotation.BinaryAnnotation("namer.path", path)))
          assert(anns.exists(_ == Annotation.BinaryAnnotation("dst.id", bound)))
          assert(anns.exists(_ == Annotation.BinaryAnnotation("dst.path", residual)))
        }
      }

      get("ralph-machio") { rsp =>
        assert(rsp.status == Status.BadGateway)
        assert(rsp.headerMap.contains(http.Headers.Err))
      }

      get("") { rsp =>
        assert(rsp.status == Status.BadRequest)
        assert(rsp.headerMap.contains(http.Headers.Err))
      }

      // todo check stats
      // todo check tracer
      //tracer.clear()
    } finally {
      await(cat.server.close())
      await(dog.server.close())
      await(router.close())
    }
  }

}
