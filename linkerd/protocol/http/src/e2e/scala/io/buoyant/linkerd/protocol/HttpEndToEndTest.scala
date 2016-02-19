package io.buoyant.linkerd
package protocol

import com.twitter.conversions.time._
import com.twitter.finagle.{Http => FinagleHttp, Status=>_, http=>_, _}
import com.twitter.finagle.buoyant.linkerd.Headers
import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.{Annotation, BufferingTracer, Trace, NullTracer}
import com.twitter.util._
import io.buoyant.router.{Http, RoutingFactory}
import io.buoyant.linkerd._
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

  test("end-to-end linking") {
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

    val yaml = s"""
routers:
- protocol: http
  baseDtab: ${dtab.show}
  httpUriInDst: true
  servers:
  - port: 0
"""

    val linker = Linker.load(yaml, Seq(HttpInitializer))
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

        val path = "/http/1.1/GET/felix"
        val bound = s"/$$/inet/127.1/${cat.port}"
        assert(rsp.headerMap.get(Headers.Dst.Path) == Some(path))
        assert(rsp.headerMap.get(Headers.Dst.Bound) == Some(bound))
        assert(rsp.headerMap.get(Headers.Dst.Residual) == None)
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
        assert(rsp.headerMap.get(Headers.Dst.Path) == Some(path))
        assert(rsp.headerMap.get(Headers.Dst.Bound) == Some(bound))
        assert(rsp.headerMap.get(Headers.Dst.Residual) == Some(residual))
        withAnnotations { anns =>
          assert(anns.exists(_ == Annotation.BinaryAnnotation("namer.path", path)))
          assert(anns.exists(_ == Annotation.BinaryAnnotation("dst.id", bound)))
          assert(anns.exists(_ == Annotation.BinaryAnnotation("dst.path", residual)))
        }
      }

      get("ralph-machio") { rsp =>
        assert(rsp.status == Status.BadGateway)
        assert(rsp.headerMap.contains(Headers.Err))
      }

      get("") { rsp =>
        assert(rsp.status == Status.BadRequest)
        assert(rsp.headerMap.contains(Headers.Err))
      }

      // todo check stats
    } finally {
      await(cat.server.close())
      await(dog.server.close())
      await(server.close())
      await(router.close())
    }
  }

}
