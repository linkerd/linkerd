package io.buoyant.grpc.runtime

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.Failure
import com.twitter.finagle.netty4.BufAsByteBuf
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.io.Buf
import com.twitter.util._
import io.buoyant.test.FunSuite
import io.netty.buffer.ByteBuf
import io.netty.handler.codec.http2._
import io.buoyant.grpc.GrpcError
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.immutable.Queue

class StreamTest extends FunSuite {
  setLogLevel(com.twitter.logging.Level.OFF)

  case class Foo(body: String)

  test("A sent message can be read once.") {
    val stream = Stream[Foo]()
    val sent = stream.send(Foo("hello"))

    await(stream.recv().transform {
      case Return(Stream.Releasable(Foo(s), release)) => {
        assert(s == "hello")
        Future.Unit
      }
      case f => fail(s"unexpected Foo: $f")
    })
  }

  test("Closing a stream allows its remaining messages to be read'") {
    val stream = Stream[Foo]()
    val sent = stream.send(Foo("hello"))
    val closed = stream.close()

    await(stream.recv().transform {
      case Return(Stream.Releasable(Foo(s), release)) => {
        assert(s == "hello")
        Future.Unit
      }
      case f => fail(s"unexpected Foo: $f")
    })
  }

  test("Resetting a stream fuses it shut") {
    val stream = Stream[Foo]()
    val sent = stream.send(Foo("hello"))
    val resetting = stream.reset(GrpcError.Internal)

    // Even though a message has been sent, we can't read it as the reader has fused the stream shut.
    await(stream.recv().transform {
      case Throw(Stream.RejectedWithReason(reason)) => {
        assert(reason == GrpcError.Internal)
        Future.Unit
      }
      case f => fail(s"unexpected return: $f")
    })

    // A reset Stream will only return the reason upon future calls to recv()
    await(stream.recv().transform {
      case Throw(Stream.RejectedWithReason(reason)) => {
        assert(reason == GrpcError.Internal)
        Future.Unit
      }
      case f => fail(s"unexpected return: $f")
    })
  }
}