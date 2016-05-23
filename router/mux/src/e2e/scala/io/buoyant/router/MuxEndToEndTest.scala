package io.buoyant.router

import com.twitter.conversions.time._
import com.twitter.finagle.{Mux=>FinagleMux, _}
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.{BufferingTracer, NullTracer}
import com.twitter.io.Buf
import com.twitter.util._
import io.buoyant.test.Awaits
import java.net.InetSocketAddress
import org.scalatest.FunSuite

class MuxEndToEndTest extends FunSuite with Awaits {

  // since this is dealing with open sockets we need to be somewhat
  // tolerant to slow test environments (cough circleci).
  override val defaultWait = 5.seconds

  /*
   * A bunch of utility/setup.  The test is configured as follows:
   *
   * - Downstreams are created. these are target services that serve
   *   requests.
   * 
   * - A Router is created that is configured with a dtab that routes
   *   certain requests by name.
   * 
   * - An Upstream is created connected to the router (or directly to
   *   the downstream).  As it is issued names, they are used to
   *   resolve a downstream through the router.
   */

  case class Downstream(name: String, server: ListeningServer) {
    val address = server.boundAddress.asInstanceOf[InetSocketAddress]
    val port = address.getPort
  }

  object Downstream {
    def service(f: Buf=>Buf) = Service.mk { req: mux.Request =>
      Future(mux.Response(f(req.body)))
    }

    def mk(name: String)(f: Buf=>Buf): Downstream =
      Downstream(name, FinagleMux.server
        .configured(param.Label(name))
        .configured(param.Tracer(NullTracer))
        .serve(":*", service(f)))

    def const(name: String, body: Buf): Downstream = mk(name)(_ => body)
    def const(name: String, body: String): Downstream = const(name, Buf.Utf8(body))
  }

  case class Router(downstreams: Seq[Downstream], names: (String, String)*) {
    val dentries =
      downstreams.map { d => Dentry.read(s"/d/${d.name} => /$$/inet/127.1/${d.port}") } ++
        names.map { case (n, d) => Dentry.read(s"/n/$n => /d/$d") }

    val dtab = Dtab(dentries.toIndexedSeq)

    val stats = NullStatsReceiver
    val tracer = new BufferingTracer
    val router = Mux.router
      .configured(param.Stats(stats))
      .configured(param.Tracer(tracer))
      .configured(RoutingFactory.BaseDtab(() => dtab))

    def make(): ListeningServer =
      Mux.serve(new InetSocketAddress(0), router.factory())
  }

  object Upstream {
    def apply(server: ListeningServer): Upstream = {
      val address = server.boundAddress.asInstanceOf[InetSocketAddress]
      val name = Name.Bound(Var.value(Addr.Bound(Address(address))), address)
      Upstream(FinagleMux.client
        .configured(param.Stats(NullStatsReceiver))
        .configured(param.Tracer(NullTracer))
        .newClient(name, "upstream").toService)
    }

    def apply(ds: Downstream): Upstream = apply(ds.server)
  }

  case class Upstream(client: Service[mux.Request, mux.Response]) {
    def apply(name: String): String = {
      val req = mux.Request(Path.Utf8("n", name), Buf.Empty)
      val Buf.Utf8(body) = await(client(req)).body
      body
    }
  }

  // sanity check without router
  test("client-server no routing") {
    val horse = Downstream.const("horse", "neigh")
    val client = Upstream(horse)
    assert(client("mred") == "neigh")
    await(horse.server.close())
    await(client.client.close())
  }

  test("end-to-end mux routing") {
    // downstream services
    val cat = Downstream.const("cat", "meow")
    val dog = Downstream.const("dog", "woof")

    // routing middle layer
    val router = Router(Seq(cat, dog),
      "felix" -> "cat",
      "clifford" -> "dog")
    val server = router.make()

    // upstream client
    val client = Upstream(server)

    assert(client("felix") == "meow")
    assert(client("clifford") == "woof")
    intercept[mux.ServerApplicationError] { client("ralphmachio") }

    // todo check stats
    // todo check tracer
    //tracer.clear()

    await(cat.server.close())
    await(dog.server.close())
    await(server.close())
    await(client.client.close())
  }

}
