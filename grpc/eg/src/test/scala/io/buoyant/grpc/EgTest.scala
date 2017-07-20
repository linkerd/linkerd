package io.buoyant.grpc

import com.google.protobuf.CodedOutputStream
import com.twitter.finagle.buoyant.H2
import com.twitter.io.Buf
import com.twitter.util.{Await, Future, Promise, Throw}
import io.buoyant.grpc.runtime._
import io.buoyant.eg.{Eg, base}
import io.buoyant.test.FunSuite
import java.nio.ByteBuffer
import java.util.Arrays

object EgTest {

  /*
   * A java-serialized protobuf message:
   *
   *     io.buoyant.eg.Message
   *       enumeration = Enumeration.THREEFOUR
   *       exception = io.buoyant.eg.base.Base.Exception{"i'ma givem hell"}
   *       path = Path{"foo", "bar", "bah", "baz"}
   */
  val msgBytes: Array[Byte] = Array(
    8, 34, 26, 17, 10, 15, 105, 39, 109, 97, 32, 103, 105,
    118, 101, 109, 32, 104, 101, 108, 108, 34, 20, 10, 3,
    102, 111, 111, 10, 3, 98, 97, 114, 10, 3, 98, 97, 104,
    10, 3, 98, 97, 122
  )
  val msgBuf = Buf.ByteArray.Owned(msgBytes)

  val expectedEnumeration = Eg.Message.Enumeration.THREEFOUR
  val expectedException = base.Exception(Some("i'ma givem hell"))

  val expectedPath = Eg.Message.Path(Seq(
    Buf.Utf8("foo"),
    Buf.Utf8("bar"),
    Buf.Utf8("bah"),
    Buf.Utf8("baz")
  ))
  val expectedMsg = Eg.Message(
    enumeration = Some(expectedEnumeration),
    result = Some(Eg.Message.OneofResult.Exception(expectedException)),
    path = Some(expectedPath)
  )
}

class EgTest extends FunSuite {
  import EgTest._

  test("sizeOf") {
    assert(Eg.Message.codec.sizeOf(expectedMsg) == msgBuf.length)
  }

  test("decode") {
    assert(Eg.Message.codec.decodeBuf(msgBuf) == expectedMsg)
  }

  test("encode") {
    val bb = ByteBuffer.allocate(msgBuf.length)
    val pbos = CodedOutputStream.newInstance(bb.duplicate())
    Eg.Message.codec.encode(expectedMsg, pbos)
    assert(Arrays.equals(bb.array, msgBytes))
  }

  test("roundtrip decode/encode") {
    val bb = ByteBuffer.allocate(msgBuf.length)
    val pbos = CodedOutputStream.newInstance(bb.duplicate())
    Eg.Message.codec.encode(Eg.Message.codec.decodeBuf(msgBuf), pbos)
    assert(Arrays.equals(bb.array, msgBytes))
  }

  test("roundtrip encode/decode") {
    val bb = ByteBuffer.allocate(msgBuf.length)
    val pbos = CodedOutputStream.newInstance(bb.duplicate())
    Eg.Message.codec.encode(expectedMsg, pbos)
    val msg = Eg.Message.codec.decodeBuf(Buf.ByteBuffer.Owned(bb))
    assert(msg == expectedMsg)
  }
}

class EgEndToEndTest extends FunSuite {
  import EgTest._

  test("unary request and unary response") {
    val sentRsp = Eg.Rsp(Some(Eg.Message.Enumeration.THREEFOUR))
    val iface = new Eg.Eggman {
      def uplatu(req: Eg.Req): Future[Eg.Rsp] = Future.value(sentRsp)
      def uplats(req: Eg.Req): Stream[Eg.Rsp] = ???
      def splatu(req: Stream[Eg.Req]): Future[Eg.Rsp] = ???
      def splats(req: Stream[Eg.Req]): Stream[Eg.Rsp] = ???
    }
    val h2srv = H2.serve(":*", ServerDispatcher(new Eg.Eggman.Server(iface)))
    val srvAddr = h2srv.boundAddress.asInstanceOf[java.net.InetSocketAddress]
    val h2client = H2.newService(s"/$$/inet/127.1/${srvAddr.getPort}")
    try {
      val client = new Eg.Eggman.Client(h2client)

      val req = Eg.Req(Some(Eg.Enumeration.TWO), Some(true))
      val rsp = await(client.uplatu(req))
      assert(rsp == sentRsp)
    } finally await(h2client.close().before(h2srv.close()))
  }

  test("unary request and streaming response") {
    val tx = Stream.mk[Eg.Rsp]
    val iface = new Eg.Eggman {
      def uplatu(req: Eg.Req): Future[Eg.Rsp] = ???
      def uplats(req: Eg.Req): Stream[Eg.Rsp] = tx
      def splatu(req: Stream[Eg.Req]): Future[Eg.Rsp] = ???
      def splats(req: Stream[Eg.Req]): Stream[Eg.Rsp] = ???
    }
    val h2srv = H2.serve(":*", new ServerDispatcher(Seq(new Eg.Eggman.Server(iface))))

    val srvAddr = h2srv.boundAddress.asInstanceOf[java.net.InetSocketAddress]
    val h2client = H2.newService(s"/$$/inet/127.1/${srvAddr.getPort}")
    val client = new Eg.Eggman.Client(h2client)

    try {
      val req = Eg.Req(Some(Eg.Enumeration.ONE), Some(true))
      val rsps = client.uplats(req)

      val rf0 = rsps.recv()
      val rf1 = rsps.recv()

      assert(!rf0.isDefined)
      await(tx.send(Eg.Rsp()))
      assert(getAndRelease(rf0) == Eg.Rsp())
      assert(!rf1.isDefined)
      await(tx.send(Eg.Rsp(Some(Eg.Message.Enumeration.ONE))))
      eventually { assert(rf1.isDefined) }
      assert(getAndRelease(rf1) == Eg.Rsp(Some(Eg.Message.Enumeration.ONE)))

      val rf2 = rsps.recv()
      assert(!rf2.isDefined)
      await(tx.close())
      assert(await(rf2.liftToTry) == Throw(GrpcStatus.Ok()))
    } finally await(h2client.close().before(h2srv.close()))
  }

  test("streaming request and unary response") {
    val rxP = new Promise[Stream[Eg.Req]]
    val rspP = new Promise[Eg.Rsp]
    val tx = Stream.mk[Eg.Rsp]
    val iface = new Eg.Eggman {
      def uplatu(req: Eg.Req): Future[Eg.Rsp] = ???
      def uplats(req: Eg.Req): Stream[Eg.Rsp] = ???
      def splatu(rx: Stream[Eg.Req]): Future[Eg.Rsp] = {
        rxP.setValue(rx)
        rspP
      }
      def splats(req: Stream[Eg.Req]): Stream[Eg.Rsp] = ???
    }
    val h2srv = H2.serve(":*", new ServerDispatcher(Seq(new Eg.Eggman.Server(iface))))

    val srvAddr = h2srv.boundAddress.asInstanceOf[java.net.InetSocketAddress]
    val h2client = H2.newService(s"/$$/inet/127.1/${srvAddr.getPort}")
    val client = new Eg.Eggman.Client(h2client)

    try {
      val tx = Stream.mk[Eg.Req]
      val rspF = client.splatu(tx)
      val rx = await(rxP)

      val rf0 = rx.recv()
      assert(!rf0.isDefined)

      val rf1 = rx.recv()
      assert(!rf1.isDefined)

      await(tx.send(Eg.Req(destroyFascism = Some(true))))
      val req0 = getAndRelease(rf0)
      assert(req0.value == None)
      assert(req0.destroyFascism == Some(true))

      assert(!rf1.isDefined)
      await(tx.send(Eg.Req(Some(Eg.Enumeration.ONE), Some(true))))
      assert(getAndRelease(rf1) == Eg.Req(Some(Eg.Enumeration.ONE), Some(true)))

      rspP.setValue(Eg.Rsp(Some(Eg.Message.Enumeration.THREEFOUR)))
      assert(await(rspF) == Eg.Rsp(Some(Eg.Message.Enumeration.THREEFOUR)))

      await(tx.close())

    } finally {
      await(h2client.close().before(h2srv.close()))
    }
  }

  test("unary request and failing unary response") {
    val iface = new Eg.Eggman {
      def uplatu(req: Eg.Req): Future[Eg.Rsp] = Future.exception(GrpcStatus.DeadlineExceeded())
      def uplats(req: Eg.Req): Stream[Eg.Rsp] = ???
      def splatu(req: Stream[Eg.Req]): Future[Eg.Rsp] = ???
      def splats(req: Stream[Eg.Req]): Stream[Eg.Rsp] = ???
    }
    val h2srv = H2.serve(":*", ServerDispatcher(new Eg.Eggman.Server(iface)))
    val srvAddr = h2srv.boundAddress.asInstanceOf[java.net.InetSocketAddress]
    val h2client = H2.newService(s"/$$/inet/127.1/${srvAddr.getPort}")
    try {
      val client = new Eg.Eggman.Client(h2client)

      val req = Eg.Req(Some(Eg.Enumeration.TWO), Some(true))
      val status = intercept[GrpcStatus] { await(client.uplatu(req)) }
      assert(status == GrpcStatus.DeadlineExceeded(""))
    } finally await(h2client.close().before(h2srv.close()))
  }

  test("unary request and failing streaming response") {
    val iface = new Eg.Eggman {
      def uplatu(req: Eg.Req): Future[Eg.Rsp] = ???
      def uplats(req: Eg.Req): Stream[Eg.Rsp] = Stream.empty(GrpcStatus.DeadlineExceeded())
      def splatu(req: Stream[Eg.Req]): Future[Eg.Rsp] = ???
      def splats(req: Stream[Eg.Req]): Stream[Eg.Rsp] = ???
    }
    val h2srv = H2.serve(":*", ServerDispatcher(new Eg.Eggman.Server(iface)))
    val srvAddr = h2srv.boundAddress.asInstanceOf[java.net.InetSocketAddress]
    val h2client = H2.newService(s"/$$/inet/127.1/${srvAddr.getPort}")
    try {
      val client = new Eg.Eggman.Client(h2client)
      val stream = client.uplats(Eg.Req(Some(Eg.Enumeration.TWO), Some(true)))
      val status = intercept[GrpcStatus] { await(stream.recv()) }
      assert(status == GrpcStatus.DeadlineExceeded())
    } finally await(h2client.close().before(h2srv.close()))
  }

  def getAndRelease[T](f: Future[Stream.Releasable[T]]): T = {
    val Stream.Releasable(v, release) = await(f)
    await(release())
    v
  }

}
