package io.buoyant.linkerd.protocol.h2

import java.net.InetSocketAddress
import com.twitter.finagle._
import com.twitter.finagle.buoyant.H2
import com.twitter.finagle.buoyant.h2.{LinkerdHeaders, Method, Request, Response, Status, Stream}
import com.twitter.finagle.stats.{InMemoryStatsReceiver, NullStatsReceiver}
import com.twitter.finagle.tracing.NullTracer
import com.twitter.util.{Future, Var}
import io.buoyant.linkerd.Linker
import io.buoyant.test.{Awaits, FunSuite}
import io.buoyant.test.h2.StreamTestUtils._

class StreamClassificationEndToEndTest extends FunSuite with Awaits {
  case class Downstream(name: String, service: Service[Request, Response]) {
    val stack = H2.server.stack.remove(LinkerdHeaders.Ctx.serverModule.role)
    val server = H2.server.withStack(stack)
      .configured(param.Label(name))
      .configured(param.Tracer(NullTracer))
      .serve(":*", service)
    val address = server.boundAddress.asInstanceOf[InetSocketAddress]
    val port = address.getPort
  }

  def upstream(server: ListeningServer) = {
    val address = Address(server.boundAddress.asInstanceOf[InetSocketAddress])
    val name = Name.Bound(Var.value(Addr.Bound(address)), address)
    val stack = H2.client.stack.remove(LinkerdHeaders.Ctx.clientModule.role)
    H2.client.withStack(stack)
      .configured(param.Stats(NullStatsReceiver))
      .configured(param.Tracer(NullTracer))
      .newClient(name, "upstream").toService
  }

  test("success") {
    val downstream = Downstream("ds", Service.mk { req =>
      Future.value(Response(Status.Ok, Stream.const("aaaaaaa")))
    })
    val config =
      s"""|routers:
          |- protocol: h2
          |  dtab: /svc/* => /$$/inet/127.1/${downstream.port}
          |  service:
          |    responseClassifier:
          |      kind: io.l5d.h2.retryableRead5XX
          |  servers:
          |  - port: 0
          |""".stripMargin

    val stats = new InMemoryStatsReceiver
    val linker = Linker.load(config).configured(param.Stats(stats))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()
    val client = upstream(server)

    val req = Request("http", Method.Get, "foo", "/", Stream.empty())
    await(client(req))

    withClue(s"after request ($req):") {
      assert(stats.counters(Seq("rt", "h2", "client", s"$$/inet/127.1/${downstream.port}", "service", "svc/foo", "success")) == 1)
      assert(stats.counters(Seq("rt", "h2", "service", "svc/foo", "success")) == 1)
      assert(stats.counters(Seq("rt", "h2", "server", "127.0.0.1/0", "success")) == 1)
    }

  }
}
