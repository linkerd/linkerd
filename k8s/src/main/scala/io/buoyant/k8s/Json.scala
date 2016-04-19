package io.buoyant.k8s

import com.fasterxml.jackson.core.{JsonParser, JsonProcessingException}
import com.fasterxml.jackson.databind._
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.concurrent.AsyncStream
import com.twitter.finagle.util.LoadService
import com.twitter.io.{Buf, Reader}
import com.twitter.logging.Logger
import com.twitter.util.{Future, Return, Throw, Try}
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.reflect.classTag

object Json {

  private[this] val log = Logger.get("k8s")

  private[this] val mapper = new ObjectMapper with ScalaObjectMapper
  mapper.registerModule(DefaultScalaModule)
  mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
  LoadService[SerializationModule].foreach { svc => mapper.registerModule(svc.module) }

  def read[T: Manifest](buf: Buf): Try[T] = {
    val Buf.ByteArray.Owned(bytes, begin, end) = Buf.ByteArray.coerce(buf)
    Try(mapper.readValue[T](bytes, begin, end - begin))
  }

  def writeBuf[T: Manifest](t: T) = Buf.ByteArray.Owned(mapper.writeValueAsBytes(t))

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
  def readChunked[T: Manifest](chunk: Buf): (Seq[T], Buf) = {
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
          json.readValueAs(classTag[T].runtimeClass) match {
            case obj: T if obj != null =>
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

  def readStream[T: Manifest](reader: Reader, bufsize: Int = 8 * 1024): AsyncStream[T] = {
    def chunks(init: Buf): AsyncStream[T] =
      for {
        chunk <- {
          log.trace("json reading chunk of %d bytes", bufsize)
          val read = reader.read(bufsize)
          read.respond {
            case Return(Some(Buf.Utf8(chunk))) =>
              log.trace("json read chunk: %s", chunk)
            case Return(None) | Throw(_: Reader.ReaderDiscarded) =>
              log.trace("json read eoc")
            case Throw(e) =>
              log.warning(e, "json read error")
          }
          AsyncStream.fromFuture(read).flatMap(AsyncStream.fromOption)
        }

        item <- {
          val (items, tail) = readChunked[T](init.concat(chunk))
          AsyncStream.fromSeq(items).concat(chunks(tail))
        }
      } yield item

    chunks(Buf.Empty)
  }

}
