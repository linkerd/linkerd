package io.buoyant.router

import com.twitter.finagle.{Status => _, param => fparam, _}
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.buoyant.h2._
import com.twitter.finagle.stats.{InMemoryStatsReceiver, NullStatsReceiver}
import com.twitter.finagle.tracing.{Annotation, BufferingTracer, NullTracer}
import com.twitter.io.Buf
import com.twitter.logging._
import com.twitter.util._
import io.buoyant.test.Awaits
import java.net.InetSocketAddress
import org.scalatest.FunSuite

class H2EndToEndTest extends FunSuite with Awaits {
  // For posterity, this is how you enable logging in a test:
  // TODO move io.buoyant.test as a utility?
  // Logger.configure(List(LoggerFactory(
  //   node = "",
  //   level = Some(Level.DEBUG),
  //   handlers = List(ConsoleHandler())
  // )))
  val log = Logger.get()

  case class Downstream(name: String, server: ListeningServer) {
    val address = server.boundAddress.asInstanceOf[InetSocketAddress]
    val port = address.getPort
    val dentry = Dentry(
      Path.read(s"/s/$name"),
      NameTree.read(s"/$$/inet/127.1/$port")
    )
  }

  object Downstream {
    def factory(name: String)(f: ClientConnection => Service[Request, Response]): Downstream = {
      val factory = new ServiceFactory[Request, Response] {
        def apply(conn: ClientConnection): Future[Service[Request, Response]] = Future(f(conn))
        def close(deadline: Time): Future[Unit] = Future.Done
      }
      val server = H2.server
        .configured(fparam.Label(name))
        .configured(fparam.Tracer(NullTracer))
        .serve(":*", factory)
      Downstream(name, server)
    }

    def mk(name: String)(f: Request=>Response): Downstream =
      factory(name) { _ =>
        Service.mk[Request, Response] { req =>
          log.debug("~~~> DOWNSTREAM SERVING REQUEST %s", req)
          Future(f(req)).respond { res =>
            log.debug("<~~~ DOWNSTREAM SERVED RESPONSE %s", res)
          }
        }
      }

    def const(name: String, value: String): Downstream =
      mk(name) { _ =>
        Response(Status.Ok, Stream.const(Buf.Utf8(value)))
      }
  }

  case class Upstream(service: Service[Request, Response]) extends Closable {

    def get(host: String, path: String = "/")(check: Option[String] => Boolean): Unit = {
      val req = Request("http", Method.Get, host, path, Stream.Nil)

      log.debug(s"~~~> UPSTREAM MAKING A REQUEST: $req")
      val rsp = await(service(req))
      log.debug(s"<~~~ UPSTREAM GOT A RESPONSE: $rsp")

      assert(rsp.status == Status.Ok)
      rsp.data match {
        case Stream.Nil =>
          assert(check(None))

        case stream: Stream.Reader =>
          await(stream.read()) match {
            case f: Frame.Data if f.isEnd =>
              val Buf.Utf8(data) = f.buf
              log.debug(s"<~~~ UPSTREAM READ FROM A STREAM: $f $data")
              assert(check(Some(data)))
              await(f.release())

            case f => fail(s"unexpected frame: $f")
          }
      }
    }

    def close(d: Time) = service.close(d)
  }

  def upstream(server: ListeningServer) = {
    val address = Address(server.boundAddress.asInstanceOf[InetSocketAddress])
    val name = Name.Bound(Var.value(Addr.Bound(address)), address)
    val client = H2.client
      .configured(fparam.Stats(NullStatsReceiver))
      .configured(fparam.Tracer(NullTracer))
      .newClient(name, "upstream").toService
    Upstream(client)
  }

  // TODO
  // test("simple flow control between client and server") {
  //   val stream = Stream()
  //   val server = Downstream.mk("server") { req =>
  //     Response(Status.Ok, stream)
  //   }
  //   val client = upstream()
  // }

  test("end-to-end routing, with prior knowledge") {
    val stats = NullStatsReceiver
    val tracer = NullTracer
    // val tracer = new BufferingTracer
    // def withAnnotations(f: Seq[Annotation] => Unit): Unit = {
    //   f(tracer.iterator.map(_.annotation).toSeq)
    //   tracer.clear()
    // }

    val cat = Downstream.const("cat", "meow")
    val dog = Downstream.const("dog", "woof")
    val router = {
      val dtab = Dtab.read(s"""
        /p/cat => /$$/inet/127.1/${cat.port} ;
        /p/dog => /$$/inet/127.1/${dog.port} ;

        /h2/felix => /p/cat ;
        /h2/clifford => /p/dog ;
      """)

      val identifierParam = H2.Identifier { _ =>
        req => {
          val dst = Dst.Path(Path.Utf8("h2", req.authority), dtab)
          Future.value(new RoutingFactory.IdentifiedRequest(dst, req))
        }
      }
      val factory = H2.router
        .configured(identifierParam)
        .factory()

      H2.serve(new InetSocketAddress(0), factory)
    }


    val client = upstream(router)
    try {
      client.get("felix")(_ == Some("meow"))
      client.get("clifford", "/the/big/red/dog")(_ == Some("woof"))

      // todo check stats
      // todo check tracer
    } finally {
      log.debug("!!!! Closing Upstream")
      await(client.close())

      log.debug("!!!! Closing Cat Downstream")
      await(cat.server.close())

      log.debug("!!!! Closing Dog Downstream")
      await(dog.server.close())

      log.debug("!!!! Closing Router")
      await(router.close())
    }
  }

}
