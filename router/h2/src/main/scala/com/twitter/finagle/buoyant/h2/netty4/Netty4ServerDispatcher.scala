package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.finagle.{Failure, Service}
import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.finagle.transport.Transport
import com.twitter.logging.Logger
import com.twitter.util.{Closable, Future, Stopwatch, Time}
import io.netty.handler.codec.http2._
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.JavaConverters._

object Netty4ServerDispatcher {
  private val log = Logger.get(getClass.getName)
}

class Netty4ServerDispatcher(
  transport: Transport[Http2Frame, Http2Frame],
  service: Service[Request, Response],
  minAccumFrames: Int,
  statsReceiver: StatsReceiver = NullStatsReceiver
) extends Closable {

  import Netty4ServerDispatcher._

  private[this] val writer = Netty4H2Writer(transport)

  private[this] val closed = new AtomicBoolean(false)

  private[this] val streams = new ConcurrentHashMap[Int, Netty4StreamTransport[Response, Request]]

  private[this] val requestMillis = statsReceiver.stat("latency_ms")
  private[this] val streamStats = statsReceiver.scope("stream")

  // Initialize a new Stream; and store it so that a response may be
  // demultiplexed to it.
  private[this] def newStreamTransport(id: Int): Netty4StreamTransport[Response, Request] = {
    val stream = Netty4StreamTransport.server(id, writer, minAccumFrames, streamStats)
    if (streams.putIfAbsent(id, stream) != null) {
      throw new IllegalStateException(s"stream ${stream.streamId} already exists")
    }
    stream.onClose.ensure {
      streams.remove(id, stream); ()
    }
    stream
  }

  override def close(deadline: Time): Future[Unit] =
    if (closed.compareAndSet(false, true)) {
      reading.raise(Failure("closed").flagged(Failure.Interrupted))
      writer.close(deadline)
    } else Future.Unit

  /**
   * Continually read from the transport, creating new streams
   */
  private[this] val reading: Future[Unit] = {
    lazy val readLoop: Http2Frame => Future[Unit] = {
      case _: Http2GoAwayFrame =>
        if (closed.compareAndSet(false, true)) transport.close()
        else Future.Unit

      case f: Http2ResetFrame =>
        f.streamId match {
          case 0 => writer.goAwayProtocolError(Time.Top)
          case id =>
            streams.get(id) match {
              case null => Future.Unit
              case stream => stream.close()
            }
        }

      case frame: Http2StreamFrame if frame.streamId > 0 =>
        val id = frame.streamId
        frame match {
          case frame: Http2HeadersFrame =>
            val stream = newStreamTransport(id)
            if (stream.offerRemoteFrame(frame)) {
              // Read the request from the stream, pass it to the
              // service to get the response, and then write the
              // response stream.
              stream.remoteMsg.flatMap(service(_)).flatMap(stream.write(_).flatten)
              if (closed.get) Future.Unit
              else transport.read().flatMap(readLoop)
            } else {
              log.error(s"server dispatcher failed to offer ${frame.name} on stream ${id}")
              stream.close()
            }

          case frame =>
            streams.get(id) match {
              case null =>
                log.error(s"server dispatcher dropping ${frame.name} message on unknown stream ${id}")
                writer.resetStreamClosed(id)

              case stream =>
                if (stream.offerRemoteFrame(frame)) {
                  if (closed.get) Future.Unit
                  else transport.read().flatMap(readLoop)
                } else {
                  log.error(s"server dispathcher failed to offer ${frame.name} on stream ${id}")
                  stream.close()
                }
            }
        }

      case frame =>
        log.warning(s"server dispatcher ignoring ${frame.name} message on the connection")
        if (closed.get) Future.Unit
        else transport.read().flatMap(readLoop)
    }

    transport.read().flatMap(readLoop).onFailure {
      case f@Failure(_) if f.isFlagged(Failure.Interrupted) =>
      case e => log.error(e, "server dispatcher")
    }
  }
}
