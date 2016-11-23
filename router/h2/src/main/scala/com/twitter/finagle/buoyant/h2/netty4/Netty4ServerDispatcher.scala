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

  private[this] val prefix = s"S L:${transport.localAddress} R:${transport.remoteAddress}"

  // Initialize a new Stream; and store it so that a response may be
  // demultiplexed to it.
  private[this] def newStreamTransport(id: Int): Netty4StreamTransport[Response, Request] = {
    val stream = Netty4StreamTransport.server(id, writer, streamStats)
    if (streams.putIfAbsent(id, stream) != null) {
      throw new IllegalStateException(s"stream ${stream.streamId} already exists")
    }
    stream.onReset.ensure {
      log.debug("[%s S:%d] removing closed stream", prefix, id)
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
      case goAway: Http2GoAwayFrame =>
        if (closed.compareAndSet(false, true)) {
          val err = Http2Error.valueOf(goAway.errorCode) match {
            case Http2Error.NO_ERROR => GoAway.NoError
            case Http2Error.PROTOCOL_ERROR => GoAway.ProtocolError
            case Http2Error.INTERNAL_ERROR => GoAway.InternalError
            case Http2Error.ENHANCE_YOUR_CALM => GoAway.EnhanceYourCalm
            case err => throw new IllegalArgumentException(s"invalid stream error: ${err}")
          }
          log.debug(err, "[%s] go away", prefix)
          serving.raise(err)
          val resetFs = streams.values.asScala.toSeq.map { s =>
            if (s.reset(Reset.Cancel)) writer.reset(s.streamId, Reset.Cancel)
            else Future.Unit
          }
          Future.join(resetFs)
        } else Future.Unit

      case frame: Http2StreamFrame if frame.streamId > 0 =>
        log.debug("[%s] received %s", prefix, frame.name)
        streams.get(frame.streamId) match {
          case null =>
            frame match {
              case frame: Http2HeadersFrame =>
                val st = newStreamTransport(frame.streamId)
                st.admitRemote(frame) match {
                  case Some(err: GoAway) => goAway(err)
                  case Some(err: Reset) => writer.reset(st.streamId, err)
                  case None =>
                    // Read the request from the stream, pass it to the
                    // service to get the response, and then write the
                    // response stream.
                    val reqF = st.remoteMsg
                    val rspF = reqF.flatMap(serve)
                    val endF = rspF.flatMap(st.write(_).flatten)
                    st.onReset.respond { t =>
                      val err = t match {
                        case Return(e) => e
                        case Throw(e) => e
                      }
                      endF.raise(err)
                    }

                    // If the service fails to process the request or
                    // the response stream fails, the stream is reset.
                    endF.respond {
                      case Return(_) => // TODO record stats here?
                      case Throw(exc) =>
                        val err = exc match {
                          case err: Reset => err
                          case _: GoAway => Reset.Cancel
                          case Failure(Some(err: Reset)) => err
                          case f@Failure(_) if f.isFlagged(Failure.Interrupted) => Reset.Cancel
                          case f@Failure(_) if f.isFlagged(Failure.Rejected) => Reset.Refused
                          case e =>
                            log.error(exc, "[%s S:%d] error", prefix, frame.streamId)
                            Reset.InternalError
                        }
                        if (st.reset(err)) {
                          log.debug(err, "[%s S:%d] writing reset", prefix, st.streamId)
                          writer.reset(st.streamId, err); ()
                        } else log.debug(err, "[%s S:%d] already reset", prefix, st.streamId)
                    }

                    if (closed.get) Future.Unit
                    else transport.read().flatMap(processLoop)
                }

              case frame =>
                log.error(Reset.Closed, "[%s S:%d] unexpected %s", prefix, frame.streamId, frame.name)
                writer.reset(frame.streamId, Reset.Closed)
            }

          case st =>
            st.admitRemote(frame) match {
              case Some(err: GoAway) =>
                log.debug(err, "[%s S:%d] go away %s", prefix, st.streamId, frame.name)
                goAway(err)

              case Some(err: Reset) =>
                log.debug(err, "[%s S:%d] unexpected %s", prefix, st.streamId, frame.name)
                writer.reset(st.streamId, err)

              case None =>
                if (closed.get) Future.Unit
                else transport.read().flatMap(processLoop)
            }
        }

      case frame =>
        log.warning("[%s] unexpected frame: %s", prefix, frame.name)
        goAway(GoAway.ProtocolError)
    }

    transport.read().flatMap(processLoop).onFailure {
      // TODO more error handling
      case e => log.error(e, "[%s] error", prefix)
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
      log.debug(err, "[%s] go away", prefix)
      serving.raise(err)
      val resetFs = streams.values.asScala.toSeq.map { s =>
        if (s.reset(Reset.Cancel)) writer.reset(s.streamId, Reset.Cancel)
        else Future.Unit
      }
      Future.join(resetFs).before(writer.goAway(err, deadline))
    } else Future.Unit

  override def close(deadline: Time): Future[Unit] =
    goAway(GoAway.NoError, deadline)

}
