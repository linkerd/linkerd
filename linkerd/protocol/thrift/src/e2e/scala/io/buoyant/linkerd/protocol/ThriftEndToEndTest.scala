package io.buoyant.linkerd.protocol

import com.twitter.finagle.buoyant.linkerd.{ThriftClientPrep, ThriftServerPrep, ThriftTraceInitializer}
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.NullTracer
import com.twitter.finagle.{Thrift => FinagleThrift, _}
import com.twitter.util.{Future, Var}
import io.buoyant.config.types.Port
import io.buoyant.router.thrift.Dest
import io.buoyant.router.thriftscala._
import io.buoyant.test.FunSuite
import java.net.InetSocketAddress

class ThriftEndToEndTest extends FunSuite {

  case class Downstream(name: String, server: ListeningServer) {
    val address = server.boundAddress.asInstanceOf[InetSocketAddress]
    val port = address.getPort
  }

  object Downstream {
    def mk(name: String)(f: String=>String): Downstream = {
      val service = new PingService[Future] {
        override def ping(msg: String): Future[String] = Future(f(msg))
      }
      val stack = FinagleThrift.server.stack
        .replace(ThriftTraceInitializer.role, ThriftTraceInitializer.serverModule[Array[Byte], Array[Byte]])
        .replace(ThriftServerPrep.role, ThriftServerPrep.module)
      val server = FinagleThrift.server.withStack(stack)
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

    val clientStack = FinagleThrift.client.stack
      .replace(ThriftClientPrep.role, ThriftClientPrep.module)

    FinagleThrift.client.withStack(clientStack)
      .configured(param.Stats(NullStatsReceiver))
      .configured(param.Tracer(NullTracer))
      .newIface[PingService[Future]](name, "upstream")
  }

  test("end-to-end echo routing") {
    val cat = Downstream.const("cat", "meow")
    val router = {
      val config = new ThriftConfig(Some(true), None) {
        dtab = Some(Dtab.read(s"""/svc/cat => /$$/inet/127.1/${cat.port} ;"""))
        servers = Seq(
          new ThriftServerConfig(None) {
            port = Some(Port(0))
          }
        )
        val c = new ThriftDefaultClient
        c.attemptTTwitterUpgrade = Some(true)
        _client = Some(c)
      }
      config.router(Stack.Params.empty).initialize().servers.head.serve()
    }

    val client = upstream(router)
    def ping(dst: Path, msg: String = "")(f: String => Unit): Unit = {
      Dest.local = dst
      val rsp = await(client.ping(msg))
      f(rsp)
    }

    try {
      ping(Path.read("/cat")) { rsp =>
        assert(rsp == "meow")
        ()
      }
    } finally {
      await(cat.server.close())
      await(router.close())
    }
  }

  test("multiple clients") {
    val cat = Downstream.const("cat", "meow")
    val router = {
      val config = new ThriftConfig(Some(true), None) {
        dtab = Some(Dtab.read(s"""/svc/cat => /$$/inet/127.1/${cat.port} ;"""))
        servers = Seq(
          new ThriftServerConfig(None) {
            port = Some(Port(0))
          }
        )
        val c = new ThriftDefaultClient
        c.attemptTTwitterUpgrade = Some(true)
        _client = Some(c)
      }
      config.router(Stack.Params.empty).initialize().servers.head.serve()
    }

    val client1 = upstream(router)
    val client2 = upstream(router)
    def ping(client: PingService[Future], dst: Path, msg: String = "")(f: String => Unit): Unit = {
      Dest.local = dst
      val rsp = await(client.ping(msg))
      f(rsp)
    }

    try {
      ping(client1, Path.read("/cat")) { rsp =>
        assert(rsp == "meow")
        ()
      }
      ping(client2, Path.read("/cat")) { rsp =>
        assert(rsp == "meow")
        ()
      }
    } finally {
      await(cat.server.close())
      await(router.close())
    }
  }

  test("linker-to-linker echo routing") {
    val cat = Downstream.const("cat", "meow")
    val incoming = {
      val config = new ThriftConfig(Some(true), None) {
        _label = Some("incoming")
        dtab = Some(Dtab.read(s"""/svc/cat => /$$/inet/127.1/${cat.port} ;"""))
        servers = Seq(
          new ThriftServerConfig(None) {
            port = Some(Port(0))
          }
        )
        val c = new ThriftDefaultClient
        c.attemptTTwitterUpgrade = Some(true)
        _client = Some(c)
      }
      config.router(Stack.Params.empty).initialize().servers.head.serve()
    }

    val outgoing = {
      val config = new ThriftConfig(Some(true), None) {
        _label = Some("outgoing")
        dtab = Some(Dtab.read(s"""/svc/cat => /$$/inet/127.1/${incoming.boundAddress.asInstanceOf[InetSocketAddress].getPort} ;"""))
        servers = Seq(
          new ThriftServerConfig(None) {
            port = Some(Port(0))
          }
        )
        val c = new ThriftDefaultClient
        c.attemptTTwitterUpgrade = Some(true)
        _client = Some(c)
      }
      config.router(Stack.Params.empty).initialize().servers.head.serve()
    }

    val client = upstream(outgoing)
    def ping(dst: Path, msg: String = "")(f: String => Unit): Unit = {
      Dest.local = dst
      val rsp = await(client.ping(msg))
      f(rsp)
    }

    try {
      ping(Path.read("/cat")) { rsp =>
        assert(rsp == "meow")
        ()
      }
    } finally {
      await(cat.server.close())
      await(incoming.close())
      await(outgoing.close())
    }
  }
}
