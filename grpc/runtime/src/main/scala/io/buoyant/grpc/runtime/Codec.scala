package io.buoyant.grpc.runtime

import com.google.protobuf.{CodedInputStream, CodedOutputStream, WireFormat}
import com.twitter.io.Buf
import com.twitter.finagle.buoyant.h2
import com.twitter.util.Future
import java.nio.{ByteBuffer, ByteOrder}

trait Codec[T] {

  val decodeByteBuffer: ByteBuffer => T =
    bb => decode(CodedInputStream.newInstance(bb))

  final val decodeBuf: Buf => T = { buf =>
    val bb = Buf.ByteBuffer.Owned.extract(buf)
    decodeByteBuffer(bb.duplicate())
  }

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

  def encodeGrpcMessage(msg: T): Buf =
    Codec.encodeGrpcMessage(msg, this)

  def decodeGrpcMessage(buf: Buf): T =
    Codec.decodeGrpcMessage(buf, this)

  val decodeRequest: h2.Request => Stream[T] =
    DecodingStream(_, decodeByteBuffer)

  val decodeResponse: h2.Response => Stream[T] =
    DecodingStream(_, decodeByteBuffer)
}

object Codec {
  val GrpcFrameHeaderSz = 5

  val decodeGrpcFrame: Buf => Buf = { buf =>
    val Buf.ByteBuffer.Owned(bb0) = Buf.ByteBuffer.coerce(buf)
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

    Buf.ByteBuffer.Owned(bb)
  }

  def bufferWithStatus(stream: h2.Stream): Future[(Buf, GrpcStatus)] = {
    def accum(orig: Buf): Future[(Buf, GrpcStatus)] =
      stream.read().flatMap {
        case t: h2.Frame.Trailers =>
          val status = GrpcStatus.fromHeaders(t)
          t.release()
          Future.value(orig -> status)

        case d: h2.Frame.Data =>
          val buf = orig.concat(d.buf)
          val isEnd = d.isEnd
          d.release()
          if (isEnd) Future.value(buf -> GrpcStatus.Unknown())
          else accum(buf)
      }

    accum(Buf.Empty)
  }

  def bufferGrpcFrame(stream: h2.Stream): Future[Buf] =
    bufferWithStatus(stream).flatMap(decodeBufferedGrpcResponseFrame)

  private[this] val decodeBufferedGrpcResponseFrame: ((Buf, GrpcStatus)) => Future[Buf] = {
    case (buf, GrpcStatus.Ok(_)) if !buf.isEmpty => Future(decodeGrpcFrame(buf))
    case (_, status) => Future.exception(status)
  }

  private def encodeGrpcMessage[T](msg: T, codec: Codec[T]): Buf = {
    val sz = codec.sizeOf(msg)
    val bb0 = ByteBuffer.allocate(GrpcFrameHeaderSz + sz)
    val bb = bb0.duplicate()
    bb.put(0.toByte) // uncompressed
    bb.putInt(sz)
    codec.encode(msg, CodedOutputStream.newInstance(bb))
    Buf.ByteBuffer.Owned(bb0)
  }

  private def decodeGrpcMessage[T](buf: Buf, codec: Codec[T]): T = {
    val Buf.ByteBuffer.Owned(bb0) = Buf.ByteBuffer.coerce(buf)
    val bb = bb0.duplicate()
    if (GrpcFrameHeaderSz > bb.remaining)
      throw new IllegalArgumentException("too short for header")

    // TODO decompress
    val compressed = bb.get == 1
    if (compressed)
      throw new IllegalArgumentException("compressed")

    val frameLen = bb.getInt
    if (frameLen > bb.remaining)
      throw new IllegalArgumentException("too short for frame")
    bb.limit(bb.position + frameLen)
    codec.decode(CodedInputStream.newInstance(bb))
  }
}
