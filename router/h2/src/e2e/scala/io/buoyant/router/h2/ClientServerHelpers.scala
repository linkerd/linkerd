package io.buoyant.router
package h2

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.{Status => _, param => fparam, _}
import com.twitter.finagle.buoyant.h2._
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.io.Buf
import com.twitter.logging.Level
import com.twitter.util._
import io.buoyant.test.FunSuite
import io.buoyant.test.h2.StreamTestUtils._
import java.net.InetSocketAddress
import org.scalatest.BeforeAndAfter
import scala.collection.JavaConverters._

trait ClientServerHelpers extends BeforeAndAfter { _: FunSuite =>
  setLogLevel(Level.OFF)

  val statsReceiver = new InMemoryStatsReceiver
  before {
    setLogLevel(Level.OFF)
    statsReceiver.clear()
  }
  after {
    setLogLevel(Level.OFF)
    statsReceiver.clear()
  }

  def mkBuf(sz: Int): Buf =
    Buf.ByteArray.Owned(Array.fill[Byte](sz)(1.toByte))

  def withClient(srv: Request => Response)(f: Upstream => Unit): Unit = {
    val server = Downstream.mk("srv")(srv)
    val client = upstream(server.server)
    try f(client)
    catch { case e: Throwable => log.info(e, "client") }
    finally {
      setLogLevel(Level.OFF)
      await(client.close())
      await(server.server.close())
    }
  }

  case class Downstream(name: String, server: ListeningServer) {
    val address = server.boundAddress.asInstanceOf[InetSocketAddress]
    val port = address.getPort
    val dentry = Dentry(
      Path.read(s"/svc/$name"),
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
        .configured(fparam.Stats(statsReceiver.scope("server")))
        .serve(":*", factory)
      Downstream(name, server)
    }

    def service(name: String)(f: Request=>Future[Response]): Downstream =
      factory(name) { _ => Service.mk[Request, Response](f) }

    def mk(name: String)(f: Request=>Response): Downstream =
      service(name) { req => Future(f(req)) }

    def const(name: String, value: String): Downstream =
      mk(name) { _ =>
        val q = new AsyncQueue[Frame]
        q.offer(Frame.Data.eos(Buf.Utf8(value)))
        Response(Status.Ok, Stream(q))
      }
  }

  case class Upstream(service: Service[Request, Response]) extends Closable {

    def apply(req: Request): Future[Response] = service(req)

    def get(host: String, path: String = "/")(check: Option[String] => Boolean): Future[Unit] = {
      val req = Request("http", Method.Get, host, path, Stream.empty())
      val rsp = await(service(req))
      assert(rsp.status == Status.Ok)
      val stream = rsp.stream.onFrame {
        case f: Frame.Data  =>
          val Buf.Utf8(data) = f.buf
          val _ = assert(check(Some(data)))
        case f =>
      }
      stream.readToEnd
    }

    def close(d: Time) = service.close(d)
  }

  def upstream(server: ListeningServer): Upstream = {
    val address = Address(server.boundAddress.asInstanceOf[InetSocketAddress])
    val name = Name.Bound(Var.value(Addr.Bound(address)), address)
    val client = H2.client
      .configured(fparam.Stats(statsReceiver.scope("client")))
      .newClient(name, "upstream").toService
    Upstream(client)
  }

}
