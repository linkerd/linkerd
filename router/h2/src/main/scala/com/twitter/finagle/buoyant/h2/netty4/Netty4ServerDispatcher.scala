package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.finagle.{ChannelClosedException, Failure, Service, WriteException}
import com.twitter.finagle.transport.Transport
import com.twitter.logging.Logger
import com.twitter.util.{Closable, Future, Return, Stopwatch, Time, Throw, Try}
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
  streamStats: Netty4StreamTransport.StatsReceiver
) extends Closable {

  import Netty4ServerDispatcher._

  private[this] val writer = Netty4H2Writer(transport)

  private[this] val closed = new AtomicBoolean(false)

  private[this] val streams = new ConcurrentHashMap[Int, Netty4StreamTransport[Response, Request]]

  // Initialize a new Stream; and store it so that a response may be
  // demultiplexed to it.
  private[this] def newStreamTransport(id: Int): Netty4StreamTransport[Response, Request] = {
    val stream = Netty4StreamTransport.server(id, writer, streamStats)
    if (streams.putIfAbsent(id, stream) != null) {
      throw new IllegalStateException(s"stream ${stream.streamId} already exists")
    }
    stream.onClose.ensure {
      streams.remove(id, stream); ()
    }
    stream
  }

  private[this] val serve: Request => Future[Response] =
    req => service(req)

  /**
   * Continually read from the transport, creating new streams
   */
  private[this] val serving: Future[Unit] = {
    lazy val processLoop: Http2Frame => Future[Unit] = {
      case _: Http2GoAwayFrame => close()

      case frame: Http2StreamFrame if frame.streamId > 0 =>
        streams.get(frame.streamId) match {
          case null =>
            frame match {
              case frame: Http2HeadersFrame =>
                val stream = newStreamTransport(frame.streamId)
                stream.admitRemote(frame) match {
                  case Some(err: GoAway) => writer.goAway(err)
                  case Some(err: Reset) => writer.reset(stream.streamId, err)
                  case None =>
                    // Read the request from the stream, pass it to the
                    // service to get the response, and then write the
                    // response stream.
                    val reqF = stream.remoteMsg
                    val rspF = reqF.flatMap(serve)
                    val endF = rspF.flatMap(stream.write(_).flatten)

                    // If the service fails to process the request or
                    // the response stream fails, the stream is reset.
                    endF.respond {
                      case Return(_) => // TODO record stats here?
                      case Throw(exc) =>
                        val err = exc match {
                          case err: Reset => err
                          case Failure(Some(err: Reset)) => err
                          case f@Failure(_) if f.isFlagged(Failure.Interrupted) => Reset.Cancel
                          case f@Failure(_) if f.isFlagged(Failure.Rejected) => Reset.Refused
                          case e =>
                            log.error(e, "%s %s: stream %d: serving error", transport.localAddress, transport.remoteAddress, frame.streamId)
                            Reset.InternalError
                        }
                        if (stream.reset(err)) {
                          writer.reset(stream.streamId, err); ()
                        }
                    }

                    if (closed.get) Future.Unit
                    else transport.read().flatMap(processLoop)
                }

              case frame =>
                log.error(s"server dispatcher unexpected ${frame.name} frame on unknown stream ${frame.streamId}")
                writer.reset(frame.streamId, Reset.Closed)
            }

          case stream =>
            stream.admitRemote(frame) match {
              case Some(err: GoAway) => writer.goAway(err)
              case Some(err: Reset) => writer.reset(frame.streamId, err)
              case None =>
                if (closed.get) Future.Unit
                else transport.read().flatMap(processLoop)
            }
        }

      case frame =>
        log.warning("%s %s: unexpected frame: %s", transport.localAddress, transport.remoteAddress, frame.name)
        goAway(GoAway.ProtocolError)
    }

    transport.read().flatMap(processLoop).onFailure {
      // TODO more error handling
      case e =>
        log.error(e, "server dispatcher")
    }
  }

  // If the connection is lost, reset active streams.
  transport.onClose.onSuccess { e =>
    if (closed.compareAndSet(false, true)) {
      log.debug(e, "%s %s: transport onClose", transport.localAddress, transport.remoteAddress)
      serving.raise(Failure("closed").flagged(Failure.Interrupted))
      streams.asScala.values.foreach(_.reset(Reset.Cancel))
    }
  }

  private[this] def goAway(err: GoAway, deadline: Time = Time.Top): Future[Unit] =
    if (closed.compareAndSet(false, true)) {
      log.info("%s %s: dispatcher close", transport.localAddress, transport.remoteAddress)
      serving.raise(err)
      val resets = streams.values.asScala.toSeq.map { s =>
        if (s.reset(Reset.Cancel)) writer.reset(s.streamId, Reset.Cancel)
        else Future.Unit
      }
      Future.join(resets).before(writer.goAway(err, deadline))
    } else Future.Unit

  override def close(deadline: Time): Future[Unit] =
    goAway(GoAway.NoError, deadline)

}
