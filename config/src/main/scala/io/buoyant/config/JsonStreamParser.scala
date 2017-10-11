package io.buoyant.config

import com.fasterxml.jackson.core.`type`.TypeReference
import com.fasterxml.jackson.core.{JsonParser, JsonProcessingException}
import com.fasterxml.jackson.databind._
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.concurrent.AsyncStream
import com.twitter.io.{Buf, Reader}
import com.twitter.logging.Logger
import com.twitter.util.{Return, Throw, Try}

import scala.collection.mutable
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
    val unexpectedEOI = "Unexpected end-of-input"

    def unapply(jpe: JsonProcessingException): Boolean =
      jpe.getMessage match {
        case null => false
        case msg => msg.startsWith(unexpectedEOI)
      }
  }

  protected[this] def parse[T](buf: Buf)(f: JsonParser => T) = {
    val Buf.ByteArray.Owned(bytes, begin, end) = Buf.ByteArray.coerce(buf)
    val parser = mapper.getFactory.createParser(bytes, begin, end - begin)
    try f(parser) finally parser.close()
  }

  /**
   * Given a chunk of bytes, read a stream of objects, and return the remaining unread buffer.
   */
  def readChunked[T: TypeReference](chunk: Buf): (Seq[T], Buf) = {
    var objs = mutable.Buffer.empty[T]
    var offset = 0L
    parse(chunk) { json =>
      var reading = true
      while (reading) {
        log.ifTrace {
          val Buf.Utf8(rest) = chunk.slice(offset.toInt, chunk.length)
          s"json chunk reading: [$offset, ${chunk.length}] $rest"
        }

        try {
          json.readValueAs[T](implicitly[TypeReference[T]]) match {
            case obj if obj != null =>
              objs.append(obj)

              val prior = offset
              offset = json.getCurrentLocation.getByteOffset
              reading = offset < chunk.length - 1

              log.ifTrace {
                val Buf.Utf8(read) = chunk.slice(prior.toInt, offset.toInt)
                s"json chunk read: [$prior, $offset] $read $obj"
              }

            case _ =>
              val Buf.Utf8(chunkstr) = chunk
              val msg = s"could not decode json object in chunk @ ${offset} bytes: ${chunkstr}"
              throw new IllegalStateException(msg)
          }
        } catch {
          case Incomplete() =>
            reading = false
            log.ifTrace {
              val Buf.Utf8(incomplete) = chunk.slice(offset.toInt, chunk.length)
              s"json chunk incomplete: [$offset, ${chunk.length}] $incomplete"
            }
        }
      }
    }

    val rest =
      if (offset >= chunk.length) Buf.Empty
      else chunk.slice(offset.toInt, chunk.length)

    (objs, rest)
  }

  private def fromReaderJson(r: Reader, chunkSize: Int = Int.MaxValue): AsyncStream[Option[Buf]] = {
    log.trace("json reading chunk of %d bytes", chunkSize)
    val read = r.read(chunkSize).respond {
      case Return(Some(Buf.Utf8(chunk))) =>
        log.trace("json read chunk: %s", chunk)
      case Return(None) | Throw(_: Reader.ReaderDiscarded) =>
        log.trace("json read eoc")
      case Throw(e) =>
        log.warning(e, "json read error")
    }.handle {
      case NonFatal(e) => None
    }

    AsyncStream.fromFuture(read).flatMap {
      //Fake None element to get around scanLeft being one behind bug
      //Tracked in https://github.com/twitter/util/issues/195
      //Can be removed once 195 is fixed
      case Some(buf) => Some(buf) +:: None +:: fromReaderJson(r, chunkSize)
      case None => AsyncStream.empty[Option[Buf]]
    }
  }

  def readStream[T: TypeReference](reader: Reader, bufsize: Int = 8 * 1024): AsyncStream[T] = {
    fromReaderJson(reader, bufsize)
      .scanLeft[(Seq[T], Buf)]((Nil, Buf.Empty))(
        (init, buf) => {
          buf match {
            case Some(b) => readChunked[T](init._2.concat(b))
            case None => (Nil, init._2)
          }
        }
      )
      .flatMap(s => AsyncStream.fromSeq(s._1))
  }

}
