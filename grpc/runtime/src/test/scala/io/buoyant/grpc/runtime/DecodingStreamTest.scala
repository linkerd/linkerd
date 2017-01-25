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
    }

    @volatile var rel0 = false
    val rel0F = () => { rel0 = true; Future.Unit }
    val f0 = decodedStream.recv()
    assert(!f0.isDefined)
    val b0: Array[Byte] = Array(0, 0, 0, 0, 5, 1, 1, 1, 1)
    assert(frameQ.offer(h2.Frame.Data(Buf.ByteArray.Owned(b0), false, rel0F)))
    assert(!f0.isDefined)

    @volatile var rel1 = false
    val rel1F = () => { rel1 = true; Future.Unit }
    val b1: Array[Byte] = Array(1, 0, 0, 0, 0, 2, 1, 1, 0, 0, 0, 0, 3, 1, 1, 1)
    assert(frameQ.offer(h2.Frame.Data(Buf.ByteArray.Owned(b1), true, rel1F)))

    eventually { assert(f0.isDefined) }
    val Stream.Releasable(v0, doRel0) = await(f0)
    assert(v0 == 5)
    assert(rel0 == false)
    assert(decodedLength == 5)

    val f1 = decodedStream.recv()
    eventually { assert(f1.isDefined) }
    val Stream.Releasable(v1, doRel1) = await(f1)
    assert(v1 == 2)
    assert(decodedLength == 7)

    val f2 = decodedStream.recv()
    assert(f2.isDefined)
    val Stream.Releasable(v2, doRel2) = await(f2)
    assert(decodedLength == 10)
    assert(rel1 == false)

    assert(rel0 == false)
    await(doRel0())
    assert(rel0 == false)
    await(doRel1())
    assert(rel0 == true)
    assert(rel1 == false)
    await(doRel2())
    assert(rel1 == true)
  }
}
