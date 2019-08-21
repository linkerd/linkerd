package io.buoyant.grpc.runtime

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.buoyant.h2
import com.twitter.finagle.buoyant.h2.Frame
import com.twitter.util._
import io.buoyant.test.FunSuite
import io.netty.buffer.Unpooled

class DecodingStreamTest extends FunSuite {

  trait FrameHarness {
    var released: Boolean

    def frame: Frame.Data
  }

  object FrameHarness {
    def apply(data: Array[Byte], last: Boolean = false) = new FrameHarness {
      override var released: Boolean = false
      override val frame: Frame.Data = {
        val rel = () => {
          released = true;
          Future.Unit
        }
        h2.Frame.Data(Unpooled.wrappedBuffer(data), eos = last, rel)

      }
    }
  }

  case class DecodingStreamHarness(frameQ: AsyncQueue[h2.Frame] = new AsyncQueue[h2.Frame]()) {
    @volatile var decodedLength: Int = 0
    val stream = new DecodingStream[Int] {
      override protected[this] val frames = h2.Stream(frameQ)
      override protected[this] val decoder = { bb: java.nio.ByteBuffer =>
        // instead of actually decoding a message, we just
        decodedLength += bb.remaining
        bb.remaining
      }
      protected[this] val getStatus: h2.Frame.Trailers => GrpcStatus = GrpcStatus.fromHeaders(_)
    }
  }
  
  test("incrementally decodes a message") {

    val streamHarness = DecodingStreamHarness()
    import streamHarness._

    // We lay out 4 pseudo-messages across 3 frames:
    val frame1 = FrameHarness(
      Array[Byte](
        0, 0, 0, 0, 2, 1, 2,
        0, 0, 0, 0, 5, 1, 2, 3, 4 // one by short
      )
    )
    val frame2 = FrameHarness(
      Array[Byte](
        5, // finishes last message
        0, 0, 0, 0, 3, 1, 2, 3
      )
    )
    val frame3 = FrameHarness(Array[Byte](0, 0, 0, 0, 4, 1, 2, 3, 4))

    val status = GrpcStatus.Unknown("idk man")

    val rcvM1 = stream.recv()
    assert(!rcvM1.isDefined)

    assert(frameQ.offer(frame1.frame))
    eventually {
      assert(rcvM1.isDefined)
    }
    val Stream.Releasable(v1, doRel1) = await(rcvM1)
    assert(v1 == 2)
    assert(frame1.released == false)
    assert(decodedLength == 2)

    val recvM2 = stream.recv()
    assert(!recvM2.isDefined)

    assert(frameQ.offer(frame2.frame))
    eventually {
      assert(recvM2.isDefined)
    }
    val Stream.Releasable(v2, doRel2) = await(recvM2)
    assert(v2 == 5)
    assert(decodedLength == 7)

    val recvM3 = stream.recv()
    eventually {
      assert(recvM3.isDefined)
    }
    val Stream.Releasable(v3, doRel3) = await(recvM3)
    assert(v3 == 3)
    assert(decodedLength == 10)

    val recvM4 = stream.recv()
    assert(!recvM4.isDefined)

    assert(frameQ.offer(frame3.frame))
    eventually {
      assert(recvM4.isDefined)
    }
    val Stream.Releasable(v4, doRel4) = await(recvM4)
    assert(v4 == 4)
    assert(decodedLength == 14)

   val recvM5 = stream.recv()
    assert(!recvM5.isDefined)

    await(doRel1())

    await(doRel3())
    assert(frame1.released == false)
    assert(frame2.released == false)

    await(doRel2())
    eventually {
      assert(frame1.released == true)
    }
    eventually {
      assert(frame2.released == true)
    }
    assert(frame3.released == false)

    await(doRel4())
    eventually {
      assert(frame3.released == true)
    }

    assert(!recvM5.isDefined)
    assert(frameQ.offer(status.toTrailers))
    eventually {
      assert(recvM5.isDefined)
    }
    assert(await(recvM5.liftToTry) == Throw(status))
  }

  test("release empty eos frame") {
    val streamHarness = DecodingStreamHarness()
    import streamHarness._

    // We have two frames, last of which is empty
    val frame1 = FrameHarness(
      Array[Byte](0, 0, 0, 0, 5, 1, 2, 3, 4, 5)
    )
    val frame2 = FrameHarness(Array.emptyByteArray, last = true)

    val recvF1= stream.recv()
    assert(!recvF1.isDefined)

    assert(frameQ.offer(frame1.frame))
    eventually {
      assert(recvF1.isDefined)
    }
    val Stream.Releasable(v0, doRel1) = await(recvF1)
    assert(v0 == 5)
    assert(frame1.released == false)
    assert(decodedLength == 5)
    
    val recvF2 = stream.recv()
    assert(!recvF2.isDefined)

    assert(frameQ.offer(frame2.frame))
    eventually {
      assert(recvF2.isDefined)
    }
    assert(await(recvF2.liftToTry) == Throw(GrpcStatus.Ok()))
    assert(decodedLength == 5)

    await(doRel1())
    eventually {
      assert(frame1.released == true)
    }
  }
}
