package io.buoyant.linkerd.telemeter

import com.google.protobuf.CodedInputStream
import com.twitter.finagle.Address.Inet
import com.twitter.finagle._
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.tracing.NullTracer
import com.twitter.io.Buf
import com.twitter.util.{Future, Promise}
import io.buoyant.config.Parser
import io.buoyant.linkerd.Linker.LinkerConfig
import io.buoyant.linkerd._
import io.buoyant.linkerd.usage.UsageMessage
import io.buoyant.namer.{NamerInitializer, TestNamerInitializer}
import io.buoyant.telemetry.MetricsTree
import io.buoyant.test.{Awaits, FunSuite}
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.Date
import scala.util.Try

class UsageDataTelemeterEndToEndTest extends FunSuite with Awaits {
  case class Downstream(name: String, server: ListeningServer, service: Service[Request, Response]) {
    val address = server.boundAddress.asInstanceOf[InetSocketAddress]
    val port = address.getPort
  }

  object Downstream {
    def mk(name: String)(f: Request=>Response): Downstream = {
      val service = Service.mk { req: Request => Future(f(req)) }
      val server = Http.server
        .configured(param.Label(name))
        .configured(param.Tracer(NullTracer))
        .serve(":*", service)
      Downstream(name, server, service)
    }
  }

  def initializer(
    protos: Seq[ProtocolInitializer] = Seq(TestProtocol.Plain, TestProtocol.Fancy),
    namers: Seq[NamerInitializer] = Seq(TestNamerInitializer)
  ) = Linker.Initializers(protocol = protos, namer = namers)

  test("telemeter sends metrics") {
    val promise = new Promise[UsageMessage]
    val proxy = Downstream.mk("proxy") { r =>
      val Buf.ByteBuffer.Owned(bb) = Buf.ByteBuffer.coerce(r.content)
      val message = UsageMessage.codec.decode(CodedInputStream.newInstance(bb))
      promise.setValue(message)
      Response()
    }

    val yaml =
      """|routers:
         |- protocol: plain
         |  servers:
         |  - port: 1
         |- protocol: fancy
         |  experimental: true
         |  servers:
         |  - port: 2
         |namers:
         |- kind: test
         |  prefix: /n
         |  buh: true
         |""".stripMargin

    val mapper = Parser.objectMapper(yaml, initializer().iter)
    val config = mapper.readValue[LinkerConfig](yaml)
    val metrics = MetricsTree()

    val requests = metrics.resolve(Seq("rt", "foo", "server", "bar", "requests")).mkCounter()
    requests.incr()
    requests.incr()

    val telemeter = new UsageDataTelemeter(
      Name.bound(Inet(proxy.address, Map())),
      withTls = false,
      config,
      metrics,
      Some("orgId"))
    telemeter.run()

    val msg = await(promise)
    assert(msg.orgId == Some("orgId"))
    assert(msg.namers == Seq("test"))
    assert(msg.routers.head.protocol == Some("plain"))
    assert(msg.routers.last.protocol == Some("fancy"))
    assert(msg.counters.head.name == Some("srv_requests"))
    assert(msg.counters.head.value == Some(2))
    assert(msg.startTime.isDefined)
    val formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'")
    assert(Try[Date](formatter.parse(msg.startTime.get)).isSuccess)
  }
}
