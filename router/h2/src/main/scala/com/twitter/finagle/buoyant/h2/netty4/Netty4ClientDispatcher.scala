package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.finagle.Service
import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.finagle.transport.Transport
import com.twitter.logging.Logger
import com.twitter.util.{Closable, Future, Return, Stopwatch, Time, Throw}
import io.netty.handler.codec.http2.Http2StreamFrame
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object Netty4ClientDispatcher {
  private val log = Logger.get(getClass.getName)
  private val MaxStreamId = math.pow(2, 31) - 1
}

/**
 * Multiplexes HTTP/2 request/responses onto HTTP/2 streams over a
 * shared connection transport.
 */
class Netty4ClientDispatcher(
  transport: Transport[Http2StreamFrame, Http2StreamFrame],
  minAccumFrames: Int,
  statsReceiver: StatsReceiver = NullStatsReceiver
) extends Service[Request, Response] {

  import Netty4ClientDispatcher._

  private[this] val writer = Netty4H2Writer(transport)

  // TODO handle overflow
  private[this] val _id = new AtomicInteger(3) // ID=1 is reserved for HTTP/1 upgrade
  private[this] def nextId() = _id.getAndAdd(2)
  private[this] val liveStreams = new ConcurrentHashMap[Int, Netty4ClientStreamTransport]

  private[this] val requestMillis = statsReceiver.stat("latency_ms")
  private[this] val streamStats = statsReceiver.scope("stream")

  @volatile private[this] var closed = false
  override def close(d: Time): Future[Unit] = {
    closed = true
    transport.close(d)
  }

  // Initialize a new Stream; and store it so that a response may be
  // demultiplexed to it.
  private[this] def newStream(): Netty4ClientStreamTransport = {
    val id = nextId()
    val stream = new Netty4ClientStreamTransport(id, writer, minAccumFrames, streamStats)
    if (liveStreams.putIfAbsent(id, stream) != null) {
      throw new IllegalStateException(s"stream ${stream.streamId} already exists")
    }
    stream.onClose.ensure {
      val _ = liveStreams.remove(id, stream)
    }
    stream
  }

  /**
   * Write a request on the underlying connection and return its
   * response when it is received.
   */
  def apply(req: Request): Future[Response] = {
    val stream = newStream()
    if (req.isEmpty) {
      stream.writeHeaders(req, eos = true)
        .before(stream.readResponse())
    } else {
      stream.writeHeaders(req).before {
        val t0 = Stopwatch.start()
        val send = stream.streamRequest(req)
        send.onSuccess(_ => requestMillis.add(t0().inMillis))

        val recv = stream.readResponse()
        recv.onFailure(send.raise)
        send.onFailure(recv.raise)
        recv
      }
    }
  }

  /**
   * Continually read frames from the HTTP2 transport. Demultiplex
   * frames from the transport onto a per-stream receive queue.
   */
  private[this] val demux = {
    def loop(): Future[Unit] =
      if (closed) Future.Unit
      else transport.read().flatMap { frame =>
        frame.streamId match {
          case 0 => log.error(s"Dropping ${frame.name} message on the connection")
          case id =>
            val enqT = Stopwatch.start()
            liveStreams.get(id) match {
              case null => log.error(s"Dropping ${frame.name} message on unknown stream ${id}")
              case stream =>
                val offered = stream.offer(frame)
                if (!offered) log.error(s"Failed to offer ${frame.name} on stream ${id}")
            }
        }
        loop()
      }

    loop().onFailure(log.error(_, "client.dispatch: readLoop"))
  }

}
