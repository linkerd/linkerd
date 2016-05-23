package io.buoyant.router

import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.buoyant.{Echo => FinagleEcho, _}
import com.twitter.finagle.client.StackClient
import com.twitter.finagle.param.ProtocolLibrary
import com.twitter.finagle.server.StackServer
import com.twitter.finagle.stack.nilStack
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.{Annotation, BufferingTracer, Trace, NullTracer}
import com.twitter.util._
import io.buoyant.test.Awaits
import java.net.{InetSocketAddress, SocketAddress}
import org.scalatest.FunSuite

class EchoEndToEndTest extends FunSuite with Awaits {

  override val defaultWait = 5.seconds

  case class Downstream(name: String, server: ListeningServer) {
    val address = server.boundAddress.asInstanceOf[InetSocketAddress]
    val port = address.getPort
    val dentry = Dentry(
      Path.read(s"/s/$name"),
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
    val stats = NullStatsReceiver
    val tracer = new BufferingTracer
    def withAnnotations(f: Seq[Annotation] => Unit): Unit = {
      f(tracer.iterator.map(_.annotation).toSeq)
      tracer.clear()
    }

    val echo = Downstream.identity("echo")
    val ohce = Downstream.reversed("ohce")
    val router = {
      val dtab = Dtab.read(s"""
        /p/echo => /$$/inet/127.1/${echo.port} ;
        /p/ohce => /$$/inet/127.1/${ohce.port} ;

        /s => /p/echo ;
        /s/idk => /$$/fail ;
        /s/dog => /p/ohce ;
      """)

      val factory = Echo.router
        .configured(RoutingFactory.BaseDtab(() => dtab))
        .configured(RoutingFactory.DstPrefix(Path.Utf8("s")))
        .configured(param.Stats(stats))
        .configured(param.Tracer(tracer))
        .factory()

      Echo.server
        .configured(param.Stats(stats))
        .configured(param.Tracer(tracer))
        .serve(new InetSocketAddress(0), factory)
    }

    val client = upstream(router)

    assert(await(client("cat")) == "cat")
    withAnnotations { anns =>
      assert(anns.exists(_ == Annotation.BinaryAnnotation("namer.path", "/s/cat")))
      assert(anns.exists(_ == Annotation.BinaryAnnotation("dst.id", s"/$$/inet/127.1/${echo.port}")))
      assert(anns.exists(_ == Annotation.BinaryAnnotation("dst.path", "/cat")))
    }

    assert(await(client("dog/bark")) == "krab/god")
    withAnnotations { anns =>
      assert(anns.exists(_ == Annotation.BinaryAnnotation("namer.path", "/s/dog/bark")))
      assert(anns.exists(_ == Annotation.BinaryAnnotation("dst.id", s"/$$/inet/127.1/${ohce.port}")))
      assert(anns.exists(_ == Annotation.BinaryAnnotation("dst.path", "/bark")))
    }

    assert(await(client("idk")) == "NOBROKERS")
    withAnnotations { anns =>
      assert(anns.exists(_ == Annotation.BinaryAnnotation("namer.path", "/s/idk")))
      assert(anns.exists(_ == Annotation.BinaryAnnotation(
        RoutingFactory.Annotations.Failure.key,
        RoutingFactory.Annotations.Failure.ClientAcquisition.name
      )))
    }

    assert(await(client("")) == "ERROR empty request")
    withAnnotations { anns =>
      assert(anns.exists(_ == Annotation.BinaryAnnotation("namer.path", "/s")))
      assert(anns.exists(_ == Annotation.BinaryAnnotation(
        RoutingFactory.Annotations.Failure.key,
        RoutingFactory.Annotations.Failure.Service.name
      )))
    }

    // todo check stats

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
        /s => /p/echo ;
      """)

    val router = Echo.router
      .configured(RoutingFactory.BaseDtab(() => dtab))
      .configured(RoutingFactory.DstPrefix(Path.Utf8("s")))
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

      def apply(req: String): Future[Dst] = {
        val path = Path.read(if (req startsWith "/") req else s"/$req")
        Future.value(Dst.Path(prefix ++ path, dtab(), Dtab.local))
      }
    }

    val pathStack: Stack[ServiceFactory[String, String]] =
      StackRouter.newPathStack[String, String] ++ (ErrorFilter +: nilStack)

    val boundStack: Stack[ServiceFactory[String, String]] =
      StackRouter.newBoundStack[String, String]

    val client: StackClient[String, String] =
      FinagleEcho.client
        .transformed(StackRouter.Client.mkStack(_))

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
          case nbae: NoBrokersAvailableException => "NOBROKERS"
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
