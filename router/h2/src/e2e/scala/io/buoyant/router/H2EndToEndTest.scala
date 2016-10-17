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
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.FunSuite
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

class H2EndToEndTest extends FunSuite with Awaits {
  // For posterity, this is how you enable logging in a test:
  // TODO move io.buoyant.test as a utility?
  Logger.configure(List(LoggerFactory(
    node = "",
    level = Some(Level.DEBUG),
    handlers = List(ConsoleHandler())
  )))
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

    def apply(req: Request): Future[Response] = service(req)

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

  test("router with prior knowledge") {
    cancel
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

  test("client/server request flow control") {
    val streamP = new Promise[Stream]
    val server = Downstream.mk("server") { req =>
      streamP.setValue(req.data)
      Response(Status.Ok, Stream.Nil)
    }
    val client = upstream(server.server)

    try {
      val writer = Stream()
      val rsp = await {
        val req = Request("http", Method.Get, "host", "/path", writer)
        client(req)
      }
      assert(rsp.status == Status.Ok)
      await(streamP) match {
        case Stream.Nil => fail(s"empty request stream")
        case reader: Stream.Reader => testFlowControl(reader, writer)
      }
    } finally {
      await(client.close())
      await(server.server.close())
    }
  }

  test("client/server response flow control") {
    val writer = Stream()
    val server = Downstream.mk("server") { _ => Response(Status.Ok, writer) }
    val client = upstream(server.server)

    try {
      val rsp = await {
        val req = Request("http", Method.Get, "host", "/path", Stream.Nil)
        client(req)
      }
      assert(rsp.status == Status.Ok)
      rsp.data match {
        case Stream.Nil => fail(s"empty response stream")
        case reader: Stream.Reader => testFlowControl(reader, writer)
      }
    } finally {
      await(client.close())
      await(server.server.close())
    }
  }

  val WindowSize = 65535
  def mkBuf(sz: Int): Buf = Buf.ByteArray.Owned(Array.fill[Byte](sz)(1.toByte))

  def testFlowControl(reader: Stream.Reader, writer: Stream.Writer[Frame]) = {
    // Put two windows' worth of data on the stream
    val release0, release1 = new Promise[Unit]
    def releaser(n: Int, p: Promise[Unit]) = () => {
      log.debug(new Exception, s"writer releasing $n")
      p.setDone()
      Future.Unit
    }

    // The first frame is too large to fit in a window. It should not
    // be released until all of the data has been flushed.  This
    // cannot happen until the reader reads and erleases some of the
    // data.
    val frame0 = Frame.Data(mkBuf(WindowSize + 1024), false, releaser(0, release0))
    val frame1 = Frame.Data(mkBuf(WindowSize - 1024), true, releaser(1, release1))
    assert(!release0.isDefined && !release1.isDefined)
    log.debug("offering frames to stream")
    assert(writer.write(frame0))
    assert(writer.write(frame1))
    assert(!release0.isDefined && !release1.isDefined)

    // Read a full window, without releasing anything.
    var read = 0
    val frames = ListBuffer.empty[Frame.Data]
    while (read < WindowSize)
      await(reader.read()) match {
        case _: Frame.Trailers => fail("unexpected trailers")
        case d: Frame.Data =>
          frames += d
          read += d.buf.length
      }
    assert(frames.nonEmpty)
    assert(frames.map(_.buf.length).sum == read)
    assert(read == WindowSize)
    assert(!release0.isDefined && !release1.isDefined)

    // At this point, we've read an entire window of data from
    // the stream without releasing any of it.  Subsequent reads
    // should not complete until data is released.
    val rf0 = reader.read()
    for (_ <- 0 to 10000) assert(!rf0.isDefined)

    // Then, we release all of the pending data so that the window
    // updates. (Note that WINDOW_UPDATE messages are aggregated so
    // that they are not sent for each individual release().)
    log.debug("reader releasing %dB from %d frames", read, frames.length)
    await(Future.collect(frames.map(_.release())).unit)
    frames.clear()

    // Now that we've released an entire window, we expect that the
    // first written frame is now released:
    assert(release0.isDefined)
    assert(!release1.isDefined)

    // Furthermore, our pending read can complete now that more data
    // has been written.
    await(rf0) match {
      case _: Frame.Trailers => fail("unexpected trailers")
      case d: Frame.Data =>
        read += d.buf.length
        // Just release it immediately.
        log.debug("reader releasing %dB from 1 frame", d.buf.length)
        await(d.release())
    }

    // Read the remaining data
    while (read < 2 * WindowSize)
      await(reader.read()) match {
        case _: Frame.Trailers => fail("unexpected trailers")
        case d: Frame.Data =>
          read += d.buf.length
          log.debug("reader releasing %dB from 1 frame", d.buf.length)
          await(d.release())
      }
    assert(read == 2 * WindowSize)
    assert(release1.isDefined)

    // There should be no remaining data (in fact, this should
    // probably throw an IllegalStateException or something, since an
    // EOS has already been served).
    val rf1 = reader.read()
    for (_ <- 0 to 10000) assert(!rf1.isDefined)
  }
}
