package io.buoyant.router

import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.thrift.ThriftClientRequest
import com.twitter.finagle.tracing.{Annotation, BufferingTracer, NullTracer}
import com.twitter.finagle.{Thrift => FinagleThrift, _}
import com.twitter.util.{Future, Var}
import io.buoyant.router.thriftscala._
import io.buoyant.test.Awaits
import java.net.InetSocketAddress
import org.scalatest.FunSuite

class ThriftEndToEndTest extends FunSuite with Awaits {

  case class Downstream(name: String, server: ListeningServer) {
    val address = server.boundAddress.asInstanceOf[InetSocketAddress]
    val port = address.getPort
  }

  object Downstream {
    def mk(name: String)(f: String=>String): Downstream = {
      val service = new PingService[Future] {
        override def ping(msg: String): Future[String] = Future(f(msg))
      }
      val server = FinagleThrift.server
        .configured(param.Label(name))
        .configured(param.Tracer(NullTracer))
        .serveIface(":*", service)
      Downstream(name, server)
    }

    def const(name: String, value: String): Downstream =
      mk(name) { _ => value }
  }

  def upstream(server: ListeningServer) = {
    val address = server.boundAddress.asInstanceOf[InetSocketAddress]
    val name = Name.Bound(Var.value(Addr.Bound(Address(address))), address)
    FinagleThrift.client
      .configured(param.Stats(NullStatsReceiver))
      .configured(param.Tracer(NullTracer))
      .newIface[PingService[Future]](name, "upstream")
  }

  test("end-to-end echo routing") {
    val stats = NullStatsReceiver
    val tracer = new BufferingTracer
    def withAnnotations(f: Seq[Annotation] => Unit): Unit = {
      f(tracer.iterator.map(_.annotation).toSeq)
      tracer.clear()
    }

    val cat = Downstream.const("cat", "meow")
    val router = {
      val dtab = Dtab.read(s"""
        /p/cat => /$$/inet/127.1/${cat.port} ;

        /thrift => /p/cat ;
      """)

      val factory = Thrift.router
        .configured(RoutingFactory.BaseDtab(() => dtab))
        .configured(RoutingFactory.DstPrefix(Path.Utf8("thrift")))
        .configured(param.Stats(stats))
        .configured(param.Tracer(tracer))
        .factory()

      val adapted = Filter.mk[Array[Byte], Array[Byte], ThriftClientRequest, Array[Byte]] { (in, svc) =>
        svc(new ThriftClientRequest(in, false))
      }.andThen(factory)

      Thrift.server
        .configured(param.Stats(stats))
        .configured(param.Tracer(tracer))
        .serve(new InetSocketAddress(0), adapted)
    }

    val client = upstream(router)
    def ping(host: String, msg: String = "")(f: String => Unit): Unit = {
      val rsp = await(client.ping(msg))
      f(rsp)
    }

    try {
      ping("felix") { rsp =>
        assert(rsp == "meow")

        val path = "/thrift"
        val bound = s"/$$/inet/127.1/${cat.port}"
        withAnnotations { anns =>
          assert(anns.contains(Annotation.Rpc("ping")))
          assert(anns.contains(Annotation.Rpc("thrift /thrift")))
          assert(anns.contains(Annotation.BinaryAnnotation("service", path)))
          assert(anns.contains(Annotation.BinaryAnnotation("client", bound)))
          assert(anns.contains(Annotation.BinaryAnnotation("residual", "/")))
          ()
        }
      }

      // todo check stats
      // todo check tracer
      //tracer.clear()
    } finally {
      await(cat.server.close())
      await(router.close())
    }
  }
}
