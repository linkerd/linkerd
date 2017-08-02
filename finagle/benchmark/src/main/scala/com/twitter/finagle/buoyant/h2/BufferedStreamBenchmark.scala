package com.twitter.finagle.buoyant.h2

import com.twitter.conversions.storage._
import com.twitter.concurrent.AsyncQueue
import com.twitter.io.Buf
import com.twitter.util.StdBenchAnnotations
import io.buoyant.test.Awaits
import org.openjdk.jmh.annotations._

trait StreamBase {
  final val Iterations = 100
  final val StreamLength = 8
  final val FrameSize = 1.kilobyte
  final val BufferCapacity = FrameSize * StreamLength
  final val Data = Buf.ByteArray(Array.fill(FrameSize.bytes.toInt)(0.toByte): _*)
}

// ./sbt 'project finagle-benchmark' 'jmh:run -i 20 -prof gc .*StreamBaselineBenchmark.*'
@State(Scope.Benchmark)
class StreamBaselineBenchmark extends StdBenchAnnotations with Awaits with StreamBase {

  var q = new AsyncQueue[Frame]()
  var stream = Stream(q)

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  @Measurement(iterations = Iterations, batchSize = StreamLength)
  @Warmup(iterations = Iterations, batchSize = StreamLength)
  def baseline(): Frame = {
    val frame = Frame.Data(Data, eos = false)
    q.offer(frame)
    val result = await(stream.read())
    result.release()
    result
  }
}

// ./sbt 'project finagle-benchmark' 'jmh:run -i 20 -prof gc .*BufferedStreamBenchmark.*'
@State(Scope.Benchmark)
class BufferedStreamBenchmark extends StdBenchAnnotations with Awaits with StreamBase {

  var q = new AsyncQueue[Frame]()
  var stream = Stream(q)

  var bufferedStream = new BufferedStream(stream, BufferCapacity.bytes)
  var child = bufferedStream.fork().get

  @Setup(Level.Iteration)
  def setup(): Unit = {
    if (bufferedStream.bufferSize >= BufferCapacity.bytes) {
      q.fail(new Exception("discard"))
      q = new AsyncQueue[Frame]()
      stream = Stream(q)
      bufferedStream.discardBuffer()
      bufferedStream = new BufferedStream(stream, BufferCapacity.bytes)
      child = bufferedStream.fork().get()
    }
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  @Measurement(iterations = Iterations, batchSize = StreamLength)
  @Warmup(iterations = Iterations, batchSize = StreamLength)
  def readFrame(): Frame = {
    val frame = Frame.Data(Data, eos = false)
    q.offer(frame)
    val result = await(child.read())
    result.release()
    result
  }

}

// ./sbt 'project finagle-benchmark' 'jmh:run -i 20 -prof gc .*BufferedStreamFanoutBenchmark.*'
@State(Scope.Benchmark)
class BufferedStreamFanoutBenchmark extends StdBenchAnnotations with Awaits with StreamBase {

  var q = new AsyncQueue[Frame]()
  var stream = Stream(q)

  @Param(Array("2", "3", "4", "5"))
  var Fanout: Int = _
  val FanoutMax = 5

  var bufferedStream = new BufferedStream(stream, BufferCapacity.bytes)
  var children: Array[Stream] = _
  var results: Array[Frame] = _

  @Setup(Level.Iteration)
  def setup(): Unit = {
    if (children == null) {
      children = new Array[Stream](FanoutMax)
      for (i <- 0 until Fanout) children(i) = bufferedStream.fork().get
      results = new Array[Frame](FanoutMax)
    }
    if (bufferedStream.bufferSize >= BufferCapacity.bytes) {
      q.fail(new Exception("discard"))
      q = new AsyncQueue[Frame]()
      stream = Stream(q)
      bufferedStream.discardBuffer()
      bufferedStream = new BufferedStream(stream, BufferCapacity.bytes)
      for (i <- 0 until Fanout) children(i) = bufferedStream.fork().get
    }
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  @Measurement(iterations = Iterations, batchSize = StreamLength)
  @Warmup(iterations = Iterations, batchSize = StreamLength)
  def fanoutFrame(): Array[Frame] = {
    val frame = Frame.Data(Data, eos = false)
    q.offer(frame)
    var i = 0
    while (i < Fanout) {
      results(i) = await(children(i).read())
      results(i).release()
      i += 1
    }
    results
  }
}
