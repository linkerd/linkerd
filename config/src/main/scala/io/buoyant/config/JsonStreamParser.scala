package io.buoyant.config

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.`type`.TypeReference
import com.fasterxml.jackson.databind._
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.concurrent.AsyncStream
import com.twitter.io.{Buf, Reader}
import com.twitter.logging.Logger
import com.twitter.util.{Future, Try}
import scala.util.control.NonFatal

class JsonStreamParser(mapper: ObjectMapper with ScalaObjectMapper) {

  private[this] val log = Logger.get

  def read[T: TypeReference](buf: Buf): Try[T] = {
    val Buf.ByteArray.Owned(bytes, begin, end) = Buf.ByteArray.coerce(buf)
    Try(mapper.readValue[T](bytes, begin, end - begin, implicitly[TypeReference[T]]))
  }

  def writeBuf[T: TypeReference](t: T) = Buf.ByteArray.Owned(mapper.writeValueAsBytes(t))

  /*
   * JSON Streaming
   */
  object EndOfStream extends Throwable

  private[this] object Incomplete {
    val unexpectedEOI = "end-of-input"

    def unapply(jpe: JsonProcessingException): Boolean =
      jpe.getMessage match {
        case null => false
        case msg => msg.contains(unexpectedEOI)
      }
  }

  private[this] def readBufObj[T: TypeReference](chunk: Buf): (Option[T], Buf) = {
    val Buf.ByteArray.Owned(bytes, begin, end) = Buf.ByteArray.coerce(chunk)
    val parser = mapper.getFactory.createParser(bytes, begin, end - begin)

    val (obj, offsetAfter) = try {
      val v = Option(parser.readValueAs[T](implicitly[TypeReference[T]]))
      val o = parser.getCurrentLocation.getByteOffset.toInt
      (v, o)
    } catch {
      case Incomplete() => (None, 0)
    } finally {
      parser.close()
    }

    log.trace("json read object: %s", obj)
    val leftover = chunk.slice(offsetAfter, chunk.length)
    (obj, leftover)
  }

  /**
   * Given a chunk of bytes, read a sequence of objects, and return the remaining unread buffer.
   */
  def readBuf[T: TypeReference](b: Buf, objs: Seq[T] = Seq.empty): (Seq[T], Buf) = {
    readBufObj(b) match {
      case (Some(o), leftover) if !leftover.isEmpty =>
        readBuf(leftover, objs :+ o)
      case (Some(o), _) =>
        (objs :+ o, Buf.Empty)
      case (None, leftover) =>
        (objs, leftover)
    }
  }

  def readStream[T: TypeReference](reader: Reader[Buf]): AsyncStream[T] = {
    // Wrap the reader so reads just terminate if a non-fatal exception occurs. This ensures the below AsyncStream ends
    // if reads fail
    val terminatingReader = new Reader[Buf] {
      def read(): Future[Option[Buf]] = reader.read().handle {
        case NonFatal(e) =>
          log.debug(e, "Error reading JSON stream")
          None
      }
      def discard(): Unit = reader.discard()
    }

    Reader.toAsyncStream(terminatingReader)
      .scanLeft[(Seq[T], Buf)]((Nil, Buf.Empty)) {
        case ((_, leftover), chunk) =>
          val b = leftover.concat(chunk)
          readBuf(b)
      }
      .flatMap {
        case (v, _) => AsyncStream.fromSeq(v)
      }
  }
}
