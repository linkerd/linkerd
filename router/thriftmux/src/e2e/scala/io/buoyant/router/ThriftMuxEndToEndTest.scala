package io.buoyant.router

import language.reflectiveCalls
import java.net.InetSocketAddress

import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.{Annotation, BufferingTracer, NullTracer}
import com.twitter.finagle.{Address, ThriftRichServer, Thrift => FinagleThrift, ThriftMux => FinagleThriftMux, _}
import com.twitter.util.{Future, Var}
import io.buoyant.router.thriftscala._
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class ThriftMuxEndToEndTest extends FunSuite with Awaits {

  object Protocol extends Enumeration {
    type Protocol = Value
    val PThrift, PThriftMux = Value
  }
  import Protocol._

  case class Downstream(name: String, server: ListeningServer) {
    val address = server.boundAddress.asInstanceOf[InetSocketAddress]
    val port = address.getPort
  }

  object Downstream {
    def mk(protocol: Protocol, name: String)(f: String=>String): Downstream = {
      val service = new PingService[Future] {
        override def ping(msg: String): Future[String] = Future(f(msg))
      }
      val server = protocol match {
        case Protocol.PThrift => FinagleThrift.server
        case Protocol.PThriftMux => FinagleThriftMux.server
      }
      Downstream(name, server
        .configured(param.Label(name))
        .configured(param.Tracer(NullTracer))
        .asInstanceOf[ThriftRichServer].serveIface(":*", service))
    }

    def const(protocol: Protocol, name: String, value: String): Downstream = mk(protocol, name) { _ => value }
  }

  case class Upstream(client: PingService[Future])

  object Upstream {
    def mk(protocol: Protocol, server: ListeningServer): Upstream = {
      val address = server.boundAddress.asInstanceOf[InetSocketAddress]
      val name = Name.Bound(Var.value(Addr.Bound(Address(address))), address)
      val client = protocol match {
        case Protocol.PThrift => FinagleThrift.client
        case Protocol.PThriftMux => FinagleThriftMux.client
      }
      Upstream(client
        .configured(param.Stats(NullStatsReceiver))
        .configured(param.Tracer(NullTracer)).asInstanceOf[ThriftRichClient]
        .newIface[PingService[Future]](name, "upstream"))
    }

    def pingWithClient(upstream: Upstream, host: String, msg: String = "")(f: String => Unit): Unit = {
      val rsp = await(upstream.client.ping(msg))
      f(rsp)
    }
  }

  def withAnnotations(tracer: BufferingTracer)(f: Seq[Annotation] => Unit): Unit = {
    f(tracer.iterator.map(_.annotation).toSeq)
    tracer.clear()
  }

  test("end-to-end echo routing - thrift server") {
    val stats = NullStatsReceiver
    val tracer = new BufferingTracer

    val cat = Downstream.const(Protocol.PThrift, "cat", "meow")
    val catRouter = {
      val dtab = Dtab.read(s"""
        /p/cat => /$$/inet/127.1/${cat.port} ;

        /thriftmux => /p/cat ;
      """)

      val factory = ThriftMux.router
        .configured(RoutingFactory.BaseDtab(() => dtab))
        .configured(RoutingFactory.DstPrefix(Path.Utf8("thriftmux")))
        .configured(param.Stats(stats))
        .configured(param.Tracer(tracer))
        .factory()

      ThriftMux.server
        .configured(param.Stats(stats))
        .configured(param.Tracer(tracer))
        .serve(new InetSocketAddress(0), ThriftMux.Router.IngestingFilter.andThen(factory))
    }

    val path = "/thriftmux"
    val bound = s"/$$/inet/127.1/${cat.port}"

    val clientThrift = Upstream.mk(Protocol.PThrift, catRouter)
    val clientThriftMux = Upstream.mk(Protocol.PThriftMux, catRouter)

    try {
      Upstream.pingWithClient(clientThrift, "felix") { rsp =>
        assert(rsp == "meow")
        withAnnotations(tracer) { anns =>
          assert(anns.contains(Annotation.BinaryAnnotation("service", path)))
          assert(anns.contains(Annotation.BinaryAnnotation("client", bound)))
          assert(anns.contains(Annotation.BinaryAnnotation("residual", "/")))
          ()
        }
      }

      Upstream.pingWithClient(clientThriftMux, "felix") { rsp =>
        assert(rsp == "meow")
        withAnnotations(tracer) { anns =>
          assert(anns.contains(Annotation.BinaryAnnotation("service", path)))
          assert(anns.contains(Annotation.BinaryAnnotation("client", bound)))
          assert(anns.contains(Annotation.BinaryAnnotation("residual", "/")))
          ()
        }
      }
    } finally {
      await(cat.server.close())
      await(catRouter.close())
    }
  }

  test("end-to-end echo routing - thriftmux server") {
    val stats = NullStatsReceiver
    val tracer = new BufferingTracer

    val dog = Downstream.const(Protocol.PThriftMux, "dog", "woof")
    val dogRouter = {
      val dtab = Dtab.read(
        s"""
        /p/dog => /$$/inet/127.1/${dog.port} ;

        /thriftmux => /p/dog ;
      """)

      val factory = ThriftMux.router
        .configured(RoutingFactory.BaseDtab(() => dtab))
        .configured(RoutingFactory.DstPrefix(Path.Utf8("thriftmux")))
        .configured(param.Stats(stats))
        .configured(param.Tracer(tracer))
        .factory()

      ThriftMux.server
        .configured(param.Stats(stats))
        .configured(param.Tracer(tracer))
        .serve(new InetSocketAddress(0), ThriftMux.Router.IngestingFilter.andThen(factory))
    }

    val path = "/thriftmux"
    val bound = s"/$$/inet/127.1/${dog.port}"

    val clientThrift = Upstream.mk(Protocol.PThrift, dogRouter)
    val clientThriftMux = Upstream.mk(Protocol.PThriftMux, dogRouter)

    try {
      Upstream.pingWithClient(clientThrift, "dog") { rsp =>
        assert(rsp == "woof")
        withAnnotations(tracer) { anns =>
          assert(anns.contains(Annotation.BinaryAnnotation("service", path)))
          assert(anns.contains(Annotation.BinaryAnnotation("client", bound)))
          assert(anns.contains(Annotation.BinaryAnnotation("residual", "/")))
          ()
        }
      }

      Upstream.pingWithClient(clientThriftMux, "dog") { rsp =>
        assert(rsp == "woof")
        withAnnotations(tracer) { anns =>
          assert(anns.contains(Annotation.BinaryAnnotation("service", path)))
          assert(anns.contains(Annotation.BinaryAnnotation("client", bound)))
          assert(anns.contains(Annotation.BinaryAnnotation("residual", "/")))
          ()
        }
      }
    } finally {
      await(dog.server.close())
      await(dogRouter.close())
    }
  }
}