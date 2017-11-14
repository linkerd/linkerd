package io.buoyant.linkerd.protocol.h2

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.buoyant.H2
import com.twitter.finagle.buoyant.h2._
import com.twitter.finagle.stats.{InMemoryStatsReceiver, NullStatsReceiver}
import com.twitter.finagle.tracing.NullTracer
import com.twitter.finagle.{param => fparam, Status => _, _}
import com.twitter.io.Buf
import com.twitter.logging.Level
import com.twitter.util.{Future, Promise, Var}
import io.buoyant.linkerd.Linker
import io.buoyant.linkerd.protocol.H2Initializer
import io.buoyant.test.FunSuite
import java.net.InetSocketAddress
import scala.collection.mutable

class H2EndToEndTest extends FunSuite {

  case class Downstream(name: String, server: ListeningServer) {
    val address = server.boundAddress.asInstanceOf[InetSocketAddress]
    val port = address.getPort
    val dentry = Dentry(
      Path.read(s"/svs/$name"),
      NameTree.read(s"/$$/inet/127.1/$port")
    )
  }

  object Downstream {
    def mk(name: String)(f: Request=>Future[Response]): Downstream = {
      val service = Service.mk { req: Request => f(req) }
      val server = H2.server
        .configured(fparam.Label(name))
        .configured(fparam.Tracer(NullTracer))
        .serve(":*", service)
      Downstream(name, server)
    }

    def const(name: String, value: String, status: Status = Status.Ok): Downstream =
      mk(name) { _ =>
        Future.value(Response(status, Stream.const(value)))
      }

    def promise(name: String): (Downstream, mutable.Seq[Promise[Response]]) = {
      val ps = mutable.MutableList[Promise[Response]]()
      val svc = mk(name) { _ =>
        val p = new Promise[Response]()
        ps += p
        p
      }
      (svc, ps)
    }
  }

  def upstream(server: ListeningServer) = {
    val address = Address(server.boundAddress.asInstanceOf[InetSocketAddress])
    val name = Name.Bound(Var.value(Addr.Bound(address)), address)
    H2.client
      .configured(fparam.Stats(NullStatsReceiver))
      .configured(fparam.Tracer(NullTracer))
      .newClient(name, "upstream").toService
  }

  def readDataStream(stream: Stream): Future[Buf] = {
    stream.read().flatMap {
      case frame: Frame.Data if frame.isEnd =>
        val buf = frame.buf
        val _ = frame.release()
        Future.value(buf)
      case frame: Frame.Data =>
        val buf = frame.buf
        val _ = frame.release()
        readDataStream(stream).map(buf.concat)
      case frame: Frame.Trailers =>
        val _ = frame.release()
        Future.value(Buf.Empty)
    }
  }

  def readDataString(stream: Stream): Future[String] =
    readDataStream(stream).map(Buf.Utf8.unapply).map(_.get)

  test("single request") {
    val stats = new InMemoryStatsReceiver

    val dog = Downstream.const("dog", "woof")


    val config =
      s"""|routers:
          |- protocol: h2
          |  experimental: true
          |  dtab: |
          |    /svc/dog => /$$/inet/127.1/${dog.port} ;
          |  servers:
          |  - port: 0
          |""".stripMargin

    val linker = Linker.Initializers(Seq(H2Initializer)).load(config)
      .configured(fparam.Stats(stats))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()

    val client = upstream(server)
    def get(host: String, path: String = "/")(f: Response => Unit) = {
      val req = Request("http", Method.Get, host, path, Stream.empty())
      val rsp = await(client(req))
      f(rsp)
    }

    get("dog") { rsp =>
      assert(rsp.status == Status.Ok)
      assert(await(readDataString(rsp.stream)) == "woof")
      ()
    }
    assert(stats.counters(Seq("rt", "h2", "client", s"$$/inet/127.1/${dog.port}", "connects")) == 1)

    await(client.close())
    await(dog.server.close())
    await(server.close())
    await(router.close())
  }

  test("concurrent requests") {
    val stats = new InMemoryStatsReceiver
    val (dog, rsps) = Downstream.promise("dog")

    val config =
      s"""|routers:
          |- protocol: h2
          |  experimental: true
          |  dtab: |
          |    /svc/dog => /$$/inet/127.1/${dog.port} ;
          |  servers:
          |  - port: 0
          |""".stripMargin

    val linker = Linker.Initializers(Seq(H2Initializer)).load(config)
      .configured(fparam.Stats(stats))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()

    val client = upstream(server)


    val req0 = Request("http", Method.Get, "dog", "/", Stream.empty())
    val fRsp0 = client(req0)

    eventually { // wait for the promise to be generated
      assert(rsps.size == 1)
    }

    val req1 = Request("http", Method.Get, "dog", "/", Stream.empty())
    val fRsp1 = client(req1)

    eventually { // wait for the promise to be generated
      assert(rsps.size == 2)
    }

    rsps(1).setValue(Response(Status.Ok, Stream.const("bow")))
    val rsp1 = await(fRsp1)
    assert(await(readDataString(rsp1.stream)) == "bow")

    rsps(0).setValue(Response(Status.Ok, Stream.const("wow")))
    val rsp0 = await(fRsp0)
    assert(await(readDataString(rsp0.stream)) == "wow")

    // should multiplex over a single connection
    assert(stats.counters(Seq("rt", "h2", "client", s"$$/inet/127.1/${dog.port}", "connects")) == 1)

    await(client.close())
    await(dog.server.close())
    await(server.close())
    await(router.close())
  }

  test("wait for stream to complete before closing") {
    val stats = new InMemoryStatsReceiver
    val (dog, rsps) = Downstream.promise("dog")

    val config =
      s"""|routers:
          |- protocol: h2
          |  experimental: true
          |  dtab: |
          |    /svc/dog => /$$/inet/127.1/${dog.port} ;
          |  servers:
          |  - port: 0
          |""".stripMargin

    val linker = Linker.Initializers(Seq(H2Initializer)).load(config)
      .configured(fparam.Stats(stats))
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()

    val client = upstream(server)

    val req = Request("http", Method.Get, "dog", "/", Stream.empty())
    val fRsp = client(req)

    eventually { // wait for the promise to be generated
      assert(rsps.size == 1)
    }

    val q = new AsyncQueue[Frame]()
    val stream = Stream(q)

    rsps(0).setValue(Response(Status.Ok, stream))

    q.offer(Frame.Data("bow", eos = false))
    q.offer(Frame.Data("wow", eos = true))

    val rsp = await(fRsp)

    assert(await(readDataString(rsp.stream)) == "bowwow")

    await(client.close())
    await(server.close())
    await(router.close())
    await(dog.server.close())
  }

}
