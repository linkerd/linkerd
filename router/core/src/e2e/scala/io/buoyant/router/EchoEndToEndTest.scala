package io.buoyant.router

import com.twitter.conversions.DurationOps._
import com.twitter.finagle._
import com.twitter.finagle.buoyant.{Echo => FinagleEcho, _}
import com.twitter.finagle.client.StackClient
import com.twitter.finagle.naming.buoyant.RichNoBrokersAvailableException
import com.twitter.finagle.param.ProtocolLibrary
import com.twitter.finagle.server.StackServer
import com.twitter.finagle.stack.nilStack
import com.twitter.finagle.stats.{InMemoryStatsReceiver, NullStatsReceiver}
import com.twitter.finagle.tracing.{Annotation, BufferingTracer, NullTracer, Trace}
import com.twitter.util._
import io.buoyant.router.RoutingFactory.{IdentifiedRequest, RequestIdentification}
import io.buoyant.test.Awaits
import java.net.{InetSocketAddress, SocketAddress}
import org.scalatest.FunSuite

class EchoEndToEndTest extends FunSuite with Awaits {

  case class Downstream(name: String, server: ListeningServer) {
    val address = server.boundAddress.asInstanceOf[InetSocketAddress]
    val port = address.getPort
    val dentry = Dentry(
      Path.read(s"/svc/$name"),
      NameTree.read(s"/$$/inet/127.1/$port")
    )
  }

  object Downstream {
    def mk(name: String)(f: String=>String): Downstream = {
      val service = Service.mk { req: String => Future(f(req)) }
      val server = FinagleEcho.server
        .configured(param.Label(name))
        .configured(param.Tracer(NullTracer))
        .serve(":*", service)
      Downstream(name, server)
    }

    def identity(name: String): Downstream = mk(name)(s => s)
    def reversed(name: String): Downstream = mk(name)(_.reverse)
  }

  def upstream(server: ListeningServer) = {
    val address = server.boundAddress.asInstanceOf[InetSocketAddress]
    val name = Name.Bound(Var.value(Addr.Bound(Address(address))), address)
    FinagleEcho.client
      .configured(param.Stats(NullStatsReceiver))
      .configured(param.Tracer(NullTracer))
      .newClient(name, "upstream").toService
  }

  test("end-to-end echo routing") {
    val stats = new InMemoryStatsReceiver
    val tracer = new BufferingTracer
    def withAnnotations(f: Seq[Annotation] => Unit): Unit = {
      f(tracer.iterator.map(_.annotation).toSeq)
      tracer.clear()
    }

    val echo = Downstream.identity("echo")
    val ohce = Downstream.reversed("ohce")
    val echoDst = s"$$/inet/127.1/${echo.port}"
    val ohceDst = s"$$/inet/127.1/${ohce.port}"
    val router = {
      val dtab = Dtab.read(s"""
        /p/echo => /${echoDst} ;
        /p/ohce => /${ohceDst} ;

        /svc => /p/echo ;
        /svc/idk => /$$/fail ;
        /svc/dog => /p/ohce ;
      """)

      val factory = Echo.router
        .configured(RoutingFactory.BaseDtab(() => dtab))
        .configured(param.Stats(stats))
        .configured(param.Tracer(tracer))
        .configured(Originator.Param(true))
        .factory()

      Echo.server
        .configured(param.Stats(stats))
        .configured(param.Tracer(tracer))
        .serve(new InetSocketAddress(0), factory)
    }

    val client = upstream(router)

    assert(await(client("cat")) == "cat")
    withAnnotations { anns =>
      assert(anns.exists(_ == Annotation.BinaryAnnotation("service", "/svc/cat")))
      assert(anns.exists(_ == Annotation.BinaryAnnotation("client", s"/${echoDst}")))
      assert(anns.exists(_ == Annotation.BinaryAnnotation("residual", "/cat")))
      ()
    }

    assert(await(client("dog/bark")) == "krab/god")
    withAnnotations { anns =>
      assert(anns.exists(_ == Annotation.BinaryAnnotation("service", "/svc/dog/bark")))
      assert(anns.exists(_ == Annotation.BinaryAnnotation("client", s"/${ohceDst}")))
      assert(anns.exists(_ == Annotation.BinaryAnnotation("residual", "/bark")))
      ()
    }

    assert(await(client("idk")) == "NOBROKERS")
    withAnnotations { anns =>
      assert(anns.exists(_ == Annotation.BinaryAnnotation("service", "/svc/idk")))
      assert(anns.exists(_ == Annotation.BinaryAnnotation(
        RoutingFactory.Annotations.Failure.key,
        RoutingFactory.Annotations.Failure.ClientAcquisition.name
      )))
      ()
    }

    assert(await(client("")) == "ERROR empty request")
    withAnnotations { anns =>
      assert(anns.exists(_ == Annotation.BinaryAnnotation("service", "/svc")))
      assert(anns.exists(_ == Annotation.BinaryAnnotation(
        RoutingFactory.Annotations.Failure.key,
        RoutingFactory.Annotations.Failure.Service.name
      )))
      ()
    }

    assert(stats.counters(Seq("client", echoDst, "requests")) == 1)
    assert(stats.counters(Seq("client", echoDst, "success")) == 1)
    assert(stats.counters(Seq("client", echoDst, "service", "svc/cat", "requests")) == 1)
    assert(stats.counters(Seq("client", echoDst, "service", "svc/cat", "success")) == 1)
    assert(stats.counters(Seq("client", ohceDst, "requests")) == 1)
    assert(stats.counters(Seq("client", ohceDst, "success")) == 1)
    assert(stats.counters(Seq("client", ohceDst, "service", "svc/dog/bark", "requests")) == 1)
    assert(stats.counters(Seq("client", ohceDst, "service", "svc/dog/bark", "success")) == 1)

    assert(stats.counters(Seq("service", "svc/cat", "requests")) == 1)
    assert(stats.counters(Seq("service", "svc/cat", "success")) == 1)
    assert(stats.counters(Seq("service", "svc/dog/bark", "requests")) == 1)
    assert(stats.counters(Seq("service", "svc/dog/bark", "success")) == 1)
    assert(stats.counters(Seq("service", "svc/idk", "requests")) == 1)
    assert(stats.counters(Seq("service", "svc/idk", "failures")) == 1)
    assert(stats.counters(Seq("service", "svc", "requests")) == 1)
    assert(stats.counters(Seq("service", "svc", "failures")) == 1)

    assert(stats.gauges(Seq("originator"))() == 1f)

    await(echo.server.close())
    await(ohce.server.close())
    await(router.close())
  }


  test("filtering") {
    def prefixFilter(pfx: String) =
      Filter.mk[String, String, String, String] { (req, svc) => svc(pfx + req) }

    val echo = Downstream.identity("echo")

    val dtab = Dtab.read(s"""
        /p/echo => /$$/inet/127.1/${echo.port} ;
        /svc => /p/echo ;
      """)

    val router = Echo.router
      .configured(RoutingFactory.BaseDtab(() => dtab))
      .pathFiltered(prefixFilter("path "))
      .boundFiltered(prefixFilter("bound "))
      .clientFiltered(prefixFilter("client "))
      .factory().toService

    assert(await(router("request")) == "client bound path request")
    await(echo.server.close())
    await(router.close())
  }
}

/*
 * We implement a dummy protocol that wraps
 * com.twitter.finagle.buoyant.Echo.
 */

object Echo extends Router[String, String] with Server[String, String] {

  object EmptyRequest extends Throwable("empty request")

  object Router {

    object ErrorFilter extends Stack.Module0[ServiceFactory[String, String]] {
      val role = Stack.Role("Error")
      val description = "handles errors n stuff"
      def make(factory: ServiceFactory[String, String]) = filter andThen factory
      val filter = Filter.mk[String, String, String, String] {
        case ("", svc) => Future.exception(EmptyRequest)
        case (req, svc) => svc(req)
      }
    }

    case class Identifier(prefix: Path = Path.empty, dtab: () => Dtab = () => Dtab.base)
        extends RoutingFactory.Identifier[String] {

      def apply(req: String): Future[RequestIdentification[String]] = {
        val path = Path.read(if (req startsWith "/") req else s"/$req")
        Future.value(
          new IdentifiedRequest(Dst.Path(prefix ++ path, dtab(), Dtab.local), req)
        )
      }
    }

    val pathStack: Stack[ServiceFactory[String, String]] =
      StackRouter.newPathStack[String, String] ++ (ErrorFilter +: nilStack)

    val boundStack: Stack[ServiceFactory[String, String]] =
      StackRouter.newBoundStack[String, String]

    val client: StackClient[String, String] =
      FinagleEcho.client
        .withStack(StackRouter.Client.mkStack(_))

    val defaultParams: Stack.Params =
      StackClient.defaultParams +
        ProtocolLibrary("echo")
  }

  case class Router(
    pathStack: Stack[ServiceFactory[String, String]] = Router.pathStack,
    boundStack: Stack[ServiceFactory[String, String]] = Router.boundStack,
    client: StackClient[String, String] = Router.client,
    params: Stack.Params = Router.defaultParams
  ) extends StdStackRouter[String, String, Router] {
    protected def copy1(
      pathStack: Stack[ServiceFactory[String, String]] = this.pathStack,
      boundStack: Stack[ServiceFactory[String, String]] = this.boundStack,
      client: StackClient[String, String] = this.client,
      params: Stack.Params = this.params
    ): Router = copy(pathStack, boundStack, client, params)

    protected def newIdentifier(): RoutingFactory.Identifier[String] = {
      val RoutingFactory.DstPrefix(pfx) = params[RoutingFactory.DstPrefix]
      val RoutingFactory.BaseDtab(baseDtab) = params[RoutingFactory.BaseDtab]
      new Router.Identifier(pfx, baseDtab)
    }
  }

  val router = Router()
  def factory() = router.factory()

  object Server {

    object ErrorModule extends Stack.Module0[ServiceFactory[String, String]] {
      val role = Stack.Role("Error")
      val description = "handles errors n stuff"
      def make(factory: ServiceFactory[String, String]) = filter andThen factory
      val filter = Filter.mk[String, String, String, String] { (req, svc) =>
        svc(req).handle {
          case nbae: RichNoBrokersAvailableException => "NOBROKERS"
          case e: Throwable => s"ERROR ${e.getMessage}"
        }
      }
    }

    val stack: Stack[ServiceFactory[String, String]] =
      ErrorModule +: FinagleEcho.server.stack

    val defaultParams: Stack.Params =
      StackServer.defaultParams +
        ProtocolLibrary("echo")
  }

  val server = FinagleEcho.Server(Server.stack, Server.defaultParams)

  def serve(
    addr: SocketAddress,
    factory: ServiceFactory[String, String]
  ): ListeningServer = server.serve(addr, factory)
}
