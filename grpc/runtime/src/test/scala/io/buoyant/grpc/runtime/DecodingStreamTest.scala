package io.buoyant.grpc.runtime

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.buoyant.h2
import com.twitter.io.Buf
import com.twitter.util._
import io.buoyant.test.FunSuite

class DecodingStreamTest extends FunSuite {

  test("incrementally decodes a message") {
    val frameQ = new AsyncQueue[h2.Frame]

    @volatile var decodedLength = 0
    val decodedStream = new DecodingStream[Int] {
      override protected[this] val frames = h2.Stream(frameQ)
      override protected[this] val decoder = { bb: java.nio.ByteBuffer =>
        // instead of actually decoding a message, we just 
        decodedLength += bb.remaining
        bb.remaining
      }
      protected[this] val getStatus: h2.Frame.Trailers => GrpcStatus = GrpcStatus.fromHeaders(_)
    }

    // We lay out 4 pseudo-messages across 3 frames:
    @volatile var released0 = false
    val frame0 = {
      val b: Array[Byte] = Array(
        0, 0, 0, 0, 2, 1, 2,
        0, 0, 0, 0, 5, 1, 2, 3, 4 // one by short
      )
      val rel = () => { released0 = true; Future.Unit }
      h2.Frame.Data(Buf.ByteArray.Owned(b), false, rel)
    }

    @volatile var released1 = false
    val frame1 = {
      val rel = () => { released1 = true; Future.Unit }
      val b: Array[Byte] = Array(
        5, // finishes last message
        0, 0, 0, 0, 3, 1, 2, 3
      )
      h2.Frame.Data(Buf.ByteArray.Owned(b), false, rel)
    }

    @volatile var released2 = false
    val frame2 = {
      val rel = () => { released2 = true; Future.Unit }
      val b: Array[Byte] = Array(0, 0, 0, 0, 4, 1, 2, 3, 4)
      h2.Frame.Data(Buf.ByteArray.Owned(b), false, rel)
    }

    val status = GrpcStatus.Unknown("idk man")

    val recvF0 = decodedStream.recv()
    assert(!recvF0.isDefined)

    assert(frameQ.offer(frame0))
    eventually { assert(recvF0.isDefined) }
    val Stream.Releasable(v0, doRel0) = await(recvF0)
    assert(v0 == 2)
    assert(released0 == false)
    assert(decodedLength == 2)

    val recvF1 = decodedStream.recv()
    assert(!recvF1.isDefined)

    assert(frameQ.offer(frame1))
    eventually { assert(recvF1.isDefined) }
    val Stream.Releasable(v1, doRel1) = await(recvF1)
    assert(v1 == 5)
    assert(decodedLength == 7)

    val recvF2 = decodedStream.recv()
    eventually { assert(recvF2.isDefined) }
    val Stream.Releasable(v2, doRel2) = await(recvF2)
    assert(v2 == 3)
    assert(decodedLength == 10)

    val recvF3 = decodedStream.recv()
    assert(!recvF3.isDefined)

    assert(frameQ.offer(frame2))
    eventually { assert(recvF3.isDefined) }
    val Stream.Releasable(v3, doRel3) = await(recvF3)
    assert(v3 == 4)
    assert(decodedLength == 14)

    val recvF4 = decodedStream.recv()
    assert(!recvF4.isDefined)

    await(doRel0())

    await(doRel2())
    assert(released0 == false)
    assert(released1 == false)

    await(doRel1())
    eventually { assert(released0 == true) }
    eventually { assert(released1 == true) }
    assert(released2 == false)

    await(doRel3())
    eventually { assert(released2 == true) }

    assert(!recvF4.isDefined)
    assert(frameQ.offer(status.toTrailers))
    eventually { assert(recvF4.isDefined) }
    assert(await(recvF4.liftToTry) == Throw(status))
  }
}
