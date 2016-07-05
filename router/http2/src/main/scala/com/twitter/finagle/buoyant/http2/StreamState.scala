package com.twitter.finagle.buoyant.http2

import com.twitter.io.Writer
import com.twitter.util.{Closable, Promise}
import io.netty.handler.codec.http2.Http2Headers

private[http2] sealed trait StreamState
private[http2] object StreamState {

  /**
   * The stream has been created but no messages have been
   * sent or received.
   */
  object Idle extends StreamState

  case class Open(
    writer: Writer with Closable,
    trailers: Promise[Option[Http2Headers]]
  ) extends StreamState

  object HalfClosedLocal extends StreamState

  case class HalfClosedRemote(
    writer: Writer with Closable,
    trailers: Promise[Option[Http2Headers]]
  ) extends StreamState

  object Closed extends StreamState
}
