package io.buoyant.grpc.runtime

import com.google.protobuf.{CodedInputStream, CodedOutputStream}
import com.twitter.finagle.buoyant.h2
import com.twitter.util.Future
import io.netty.buffer.{ByteBuf, Unpooled}
import java.nio.ByteBuffer

trait Codec[T] {

  val decodeByteBuffer: ByteBuffer => T =
    bb => decode(CodedInputStream.newInstance(bb))

  def decode: CodedInputStream => T

  val decodeEmbedded: CodedInputStream => T = { pbis =>
    val length = pbis.readRawVarint32()
    val origLimit = pbis.pushLimit(length)
    val t = decode(pbis)
    pbis.popLimit(origLimit)
    t
  }

  def encode(t: T, pbos: CodedOutputStream): Unit
  def encodeEmbedded(t: T, pbos: CodedOutputStream): Unit = {
    pbos.writeUInt32NoTag(sizeOf(t))
    encode(t, pbos)
  }

  def sizeOf(t: T): Int
  def sizeOfEmbedded(t: T): Int = {
    val size = sizeOf(t)
    CodedOutputStream.computeUInt32SizeNoTag(size) + size
  }

  def encodeGrpcMessage(msg: T): ByteBuf =
    Codec.encodeGrpcMessage(msg, this)

  def decodeGrpcMessage(buf: ByteBuf): T =
    Codec.decodeGrpcMessage(buf, this)

  val decodeRequest: h2.Request => Stream[T] =
    DecodingStream(_, decodeByteBuffer)

  val decodeResponse: h2.Response => Stream[T] =
    DecodingStream(_, decodeByteBuffer)
}

object Codec {
  val GrpcFrameHeaderSz = 5

  val decodeGrpcFrame: ByteBuffer => ByteBuffer = { bb0 =>
    val bb = bb0.duplicate()
    if (GrpcFrameHeaderSz > bb.remaining)
      throw new IllegalArgumentException(s"too short for header: $bb")

    // TODO decompress
    val compressed = bb.get == 1
    if (compressed)
      throw new IllegalArgumentException("compressed")

    val frameLen = bb.getInt
    if (frameLen > bb.remaining)
      throw new IllegalArgumentException("too short for frame")
    bb.limit(bb.position + frameLen)
    bb
  }

  def bufferWithStatus(stream: h2.Stream): Future[(ByteBuffer, GrpcStatus)] = {

    val accum = Unpooled.compositeBuffer()

    def loop(): Future[GrpcStatus] =
      stream.read().flatMap {
        case t: h2.Frame.Trailers =>
          val status = GrpcStatus.fromHeaders(t)
          t.release()
          Future.value(status)

        case d: h2.Frame.Data =>
          // Copy the buffer so that the frame and its buffer can be safely released.
          accum.addComponent(true, Unpooled.copiedBuffer(d.buf))
          val isEnd = d.isEnd
          d.release()
          if (isEnd) Future.value(GrpcStatus.Unknown())
          else loop()
      }

    loop().map { status =>
      // copy the data into a nio ByteBuffer
      accum.nioBuffer() -> status
    }
  }

  def bufferGrpcFrame(stream: h2.Stream): Future[ByteBuffer] =
    bufferWithStatus(stream).flatMap(decodeBufferedGrpcResponseFrame)

  private[this] val decodeBufferedGrpcResponseFrame: ((ByteBuffer, GrpcStatus)) => Future[ByteBuffer] = {
    case (buf, GrpcStatus.Ok(_)) if buf.hasRemaining => Future(decodeGrpcFrame(buf))
    case (_, status) => Future.exception(status)
  }

  private def encodeGrpcMessage[T](msg: T, codec: Codec[T]): ByteBuf = {
    val sz = codec.sizeOf(msg)
    val bb = ByteBuffer.allocate(GrpcFrameHeaderSz + sz)
    bb.put(0.toByte) // uncompressed
    bb.putInt(sz)
    val cos = CodedOutputStream.newInstance(bb)
    codec.encode(msg, cos)
    cos.flush()
    bb.flip()
    Unpooled.wrappedBuffer(bb)
  }

  private def decodeGrpcMessage[T](buf: ByteBuf, codec: Codec[T]): T = {
    if (GrpcFrameHeaderSz > buf.readableBytes())
      throw new IllegalArgumentException("too short for header")

    val dup = buf.duplicate()
    // TODO decompress
    val compressed = dup.readByte() == 1
    if (compressed)
      throw new IllegalArgumentException("compressed")

    val frameLen = dup.readInt()
    if (frameLen > dup.readableBytes)
      throw new IllegalArgumentException("too short for frame")
    val nioBuf = dup.nioBuffer(dup.readerIndex, frameLen)
    codec.decode(CodedInputStream.newInstance(nioBuf))
  }
}
