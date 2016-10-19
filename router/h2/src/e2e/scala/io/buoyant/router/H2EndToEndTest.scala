package io.buoyant.router

import com.twitter.finagle.{Status => _, param => fparam, _}
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.buoyant.h2._
import com.twitter.finagle.stats.{InMemoryStatsReceiver, NullStatsReceiver}
import com.twitter.finagle.tracing.{Annotation, BufferingTracer, NullTracer}
import com.twitter.io.Buf
import com.twitter.logging.{Level, Logger}
import com.twitter.util._
import io.buoyant.test.FunSuite
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

class H2EndToEndTest extends FunSuite {
  val log = Logger.get()
  val LargeStreamLen = 100L * 1024 * 1024 // == 100MB
  logLevel = Level.OFF

  test("client/server request flow control") {
    val streamP = new Promise[Stream]
    val server = Downstream.mk("server") { req =>
      streamP.setValue(req.data)
      Response(Status.Ok, Stream.Nil)
    }
    val client = upstream(server.server)
    try {
      val writer = Stream()
      val req = Request("http", Method.Get, "host", "/path", writer)
      val rsp = await(client(req))
      assert(rsp.status == Status.Ok)
      testFlowControl(reader(await(streamP)), writer)
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
      val req = Request("http", Method.Get, "host", "/path", Stream.Nil)
      val rsp = await(client(req))
      assert(rsp.status == Status.Ok)
      testFlowControl(reader(rsp.data), writer)
    } finally {
      await(client.close())
      await(server.server.close())
    }
  }

  test("client/server large request stream") {
    val streamP = new Promise[Stream]
    val server = Downstream.mk("server") { req =>
      streamP.setValue(req.data)
      Response(Status.Ok, Stream.Nil)
    }
    val client = upstream(server.server)
    try {
      val writer = Stream()
      val req = Request("http", Method.Get, "host", "/path", writer)
      val rsp = await(client(req))
      assert(rsp.status == Status.Ok)
      testLargeStream(reader(await(streamP)), writer, LargeStreamLen)
    } finally {
      await(client.close())
      await(server.server.close())
    }
  }

  test("client/server large response stream") {
    val writer = Stream()
    val server = Downstream.mk("server") { _ => Response(Status.Ok, writer) }
    val client = upstream(server.server)
    try {
      val req = Request("http", Method.Get, "host", "/path", Stream.Nil)
      val rsp = await(client(req))
      assert(rsp.status == Status.Ok)
      testLargeStream(reader(rsp.data), writer, LargeStreamLen)
    } finally {
      await(client.close())
      await(server.server.close())
    }
  }

  test("router with prior knowledge") {
    val stats = NullStatsReceiver
    val tracer = NullTracer
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
    } finally {
      await(client.close())
      await(cat.server.close())
      await(dog.server.close())
      await(router.close())
    }
  }

  /*
   * Helpers
   */

  def reader(s: Stream) = s match {
    case Stream.Nil => fail("empty response stream")
    case r: Stream.Reader => r
  }

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
          Future(f(req))
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
      val rsp = await(service(req))
      assert(rsp.status == Status.Ok)
      rsp.data match {
        case Stream.Nil =>
          assert(check(None))

        case stream: Stream.Reader =>
          await(stream.read()) match {
            case f: Frame.Data if f.isEnd =>
              val Buf.Utf8(data) = f.buf
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

  val WindowSize = 65535
  def mkBuf(sz: Int): Buf = Buf.ByteArray.Owned(Array.fill[Byte](sz)(1.toByte))

  def testFlowControl(reader: Stream.Reader, writer: Stream.Writer[Frame]) = {
    // Put two windows' worth of data on the stream
    val release0, release1 = new Promise[Unit]
    def releaser(p: Promise[Unit]) = () => {
      p.setDone()
      Future.Unit
    }

    // The first frame is too large to fit in a window. It should not
    // be released until all of the data has been flushed.  This
    // cannot happen until the reader reads and erleases some of the
    // data.
    val frame0 = Frame.Data(mkBuf(WindowSize + 1024), false, releaser(release0))
    val frame1 = Frame.Data(mkBuf(WindowSize - 1024), true, releaser(release1))
    assert(!release0.isDefined && !release1.isDefined)
    log.debug("offering 2 frames with %d", 2 * WindowSize)
    assert(writer.write(frame0))
    assert(writer.write(frame1))
    assert(!release0.isDefined && !release1.isDefined)

    // Read a full window, without releasing anything.
    var read = 0
    val frames = ListBuffer.empty[Frame.Data]
    while (read < WindowSize) {
      await(reader.read()) match {
        case _: Frame.Trailers => fail("unexpected trailers")
        case d: Frame.Data =>
          frames += d
          read += d.buf.length
      }
    }
    assert(frames.map(_.buf.length).sum == read)
    assert(read == WindowSize)
    assert(!release0.isDefined && !release1.isDefined)

    // At this point, we've read an entire window of data from
    // the stream without releasing any of it.  Subsequent reads
    // should not complete until data is released.
    val rf0 = reader.read()
    for (_ <- 0 to 10000) assert(!rf0.isDefined)

    // Then, we release all of the pending data so that the window
    // updates.  Because window updates are aggregated, we can't be
    // incremental or we may not actually send an update.
    log.debug("reader releasing %dB from %d frames", read, frames.length-1)
    await(Future.collect(frames.map(_.release())).unit)
    frames.clear()

    // The second frame (release1) may or not have been released at
    // this point, depending on what window updates have been sent.

    // The pending read can complete now that window has opened and
    // the remote can send more.
    await(rf0) match {
      case _: Frame.Trailers => fail("unexpected trailers")
      case d: Frame.Data =>
        read += d.buf.length
        // Just release it immediately.
        log.debug("reader releasing %dB from 1 frame", d.buf.length)
        await(d.release())
    }

    // Read the remaining data and ensure that the second frame is released.
    while (read < 2 * WindowSize) {
      log.debug("reader reading more after %dB/%dB, released=%s",
        read, 2 * WindowSize, release1.isDefined)
      await(reader.read()) match {
        case _: Frame.Trailers => fail("unexpected trailers")
        case d: Frame.Data =>
          read += d.buf.length
          log.debug("reader releasing %dB from 1 frame", d.buf.length)
          await(d.release())
          assert(d.isEnd == (read == 2 * WindowSize))
      }
    }
    assert(read == 2 * WindowSize)
    eventually { assert(release1.isDefined) }
  }

  def testLargeStream(
    reader: Stream.Reader,
    writer: Stream.Writer[Frame],
    streamLen: Long
  ) = {
    @tailrec def loop(bytesWritten: Long, ending: Boolean): Unit = {
      assert(bytesWritten <= streamLen)
      await(reader.read()) match {
        case t: Frame.Trailers =>
          fail(s"unexpected trailers $t")
        case d: Frame.Data if d.isEnd =>
          await(d.release())
          assert(bytesWritten == streamLen)
        case d: Frame.Data =>
          val eos = bytesWritten + d.buf.length >= streamLen
          val len = math.min(d.buf.length, streamLen - bytesWritten)
          val frame = Frame.Data(mkBuf(len.toInt), eos)
          await(d.release())
          if (!ending) {
            assert(writer.write(frame), s"failed to give frame $frame")
            loop(bytesWritten + len, eos)
          }
      }
    }

    assert(writer.write(Frame.Data(mkBuf(16 * 1024), false)))
    loop(16 * 1024, false)
  }
}
