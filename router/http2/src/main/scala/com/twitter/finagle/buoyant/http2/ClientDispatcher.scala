package com.twitter.finagle.buoyant.http2

import com.twitter.finagle.Service
import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.logging.Logger
import com.twitter.util.{Closable, Future, Return, Stopwatch, Time, Throw}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object ClientDispatcher {
  private val log = Logger.get(getClass.getName)
  private val MaxStreamId = math.pow(2, 31) - 1
}

/**
 * Multiplexes HTTP/2 request/responses onto HTTP/2 streams over a
 * shared connection transport.
 */
class ClientDispatcher(
  transport: Http2Transport,
  minAccumFrames: Int = 10,
  statsReceiver: StatsReceiver = NullStatsReceiver
) extends Service[Request, Response] {

  import ClientDispatcher._

  // TODO handle overflow
  private[this] val _id = new AtomicInteger(3) // ID=1 is reserved for HTTP/1 upgrade
  private[this] def nextId() = _id.getAndAdd(2)
  private[this] val liveStreams = new ConcurrentHashMap[Int, ClientStreamTransport]

  private[this] val streamStats = statsReceiver.scope("stream")

  private[this] val recvqOfferMicros = streamStats.stat("recvq", "offer_us")
  private[this] val requestMillis = statsReceiver.stat("request_duration_ms")

  @volatile private[this] var closed = false
  override def close(d: Time): Future[Unit] = {
    closed = true
    transport.close()
  }

  // Initialize a new Stream; and store it so that a response may be
  // demultiplexed to it.
  private[this] def newStream(): ClientStreamTransport = {
    val id = nextId()
    val stream = new ClientStreamTransport(
      id, transport,
      minAccumFrames = minAccumFrames,
      statsReceiver = streamStats
    )
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
        val clock = Stopwatch.start()
        val tx = stream.streamRequest(req)
        tx.onSuccess(_ => requestMillis.add(clock().inMillis))

        val rx = stream.readResponse()
        rx.onFailure(tx.raise)
        tx.onFailure(rx.raise)
        rx
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
                stream.recvq.offer(frame)
                recvqOfferMicros.add(enqT().inMicroseconds)
            }
        }
        loop()
      }

    loop().onFailure(log.error(_, "client.dispatch: readLoop"))
  }

}
