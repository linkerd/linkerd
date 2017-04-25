package io.netty.handler.codec.http2

import io.netty.buffer.ByteBuf;
import io.netty.channel.{ChannelDuplexHandler, ChannelHandlerContext, ChannelPromise}
import io.netty.handler.codec.http.HttpServerUpgradeHandler.UpgradeEvent;

/**
 * This is a direct reimplementation of io.netty.handler.codec.http2.Http2FrameCodec.
 *
 * Thie Netty API is unstable at the moment and unsuitable for our
 * current needs, especially with regard to initial settings and flow
 * control.  We SHOULD feed back whatever we need to Netty, but one
 * step at a time.
 *
 * Copyright 2016 The Netty Project
 */
class H2FrameCodec(
  http2Handler: Http2ConnectionHandler
) extends ChannelDuplexHandler {

  import H2FrameCodec._

  private[this] var channelCtx, http2HandlerCtx: ChannelHandlerContext = null

  private[this] val connectionListener = new Http2ConnectionAdapter {

    override def onStreamActive(stream: Http2Stream): Unit = channelCtx match {
      case null => // UPGRADE stream is active before handlerAdded
      case ctx => ctx.fireUserEventTriggered(new Http2StreamActiveEvent(stream.id)); ()
    }

    override def onStreamClosed(stream: Http2Stream): Unit = {
      channelCtx.fireUserEventTriggered(new Http2StreamClosedEvent(stream.id)); ()
    }

    override def onGoAwayReceived(lastStreamId: Int, errorCode: Long, data: ByteBuf): Unit = {
      channelCtx.fireChannelRead(new DefaultHttp2GoAwayFrame(lastStreamId, errorCode, data)); ()
    }
  }

  http2Handler.connection.addListener(connectionListener)

  def connectionHandler: Http2ConnectionHandler = http2Handler

  /**
   * Load any dependencies.
   */
  override def handlerAdded(ctx: ChannelHandlerContext): Unit = {
    channelCtx = ctx
    ctx.pipeline.addBefore(ctx.executor, ctx.name, null, http2Handler)
    http2HandlerCtx = ctx.pipeline.context(http2Handler)
  }

  /**
   * Clean up any dependencies.
   */
  override def handlerRemoved(ctx: ChannelHandlerContext): Unit = {
    ctx.pipeline.remove(http2Handler); ()
  }

  /**
   * Handles the cleartext HTTP upgrade event. If an upgrade occurred,
   * sends a simple response via HTTP/2 on stream 1 (the stream
   * specifically reserved for cleartext HTTP upgrade).
   */
  override def userEventTriggered(ctx: ChannelHandlerContext, ev: Any): Unit =
    ev match {
      case upgrade: UpgradeEvent =>
        try {
          val stream = http2Handler.connection.stream(Http2CodecUtil.HTTP_UPGRADE_STREAM_ID)
          // TODO: improve handler/stream lifecycle so that stream isn't
          // active before handler added.  The stream was already made
          // active, but ctx may have been null so it wasn't
          // initialized.  https://github.com/netty/netty/issues/4942
          connectionListener.onStreamActive(stream)

          upgrade.upgradeRequest.headers.setInt(
            HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text,
            Http2CodecUtil.HTTP_UPGRADE_STREAM_ID
          )

          val adapter = new InboundHttpToHttp2Adapter(
            http2Handler.connection,
            http2Handler.decoder.frameListener
          )
          adapter.channelRead(ctx, upgrade.upgradeRequest.retain())
        } finally { upgrade.release(); () }

      case ev => super.userEventTriggered(ctx, ev)
    }

  // Override this to signal it will never throw an exception.
  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
    ctx.fireExceptionCaught(cause); ()
  }

  /**
   * Processes all Http2Frame messages. Http2StreamFrame messages
   * may only originate in child streams.
   */
  override def write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise): Unit =
    msg match {
      case goaway: Http2GoAwayFrame =>
        try {
          if (goaway.lastStreamId > -1) {
            throw new IllegalArgumentException("Last stream ID must not be set on GOAWAY")
          }
          val lastStreamCreated = http2Handler.connection.remote.lastStreamCreated
          val lastStreamId = (lastStreamCreated + goaway.extraStreamIds * 2) match {
            case n if n < lastStreamCreated => Int.MaxValue
            case n => n
          }
          http2Handler.goAway(
            http2HandlerCtx,
            lastStreamId,
            goaway.errorCode,
            goaway.content.retain(),
            promise
          ); ()
        } finally { goaway.release(); () }

      case data: Http2DataFrame =>
        try {
          http2Handler.encoder.writeData(
            http2HandlerCtx,
            data.streamId,
            data.content.retain(),
            data.padding,
            data.isEndStream,
            promise
          ); ()
        } finally { data.release(); () }

      case headers: Http2HeadersFrame =>
        http2Handler.encoder.writeHeaders(
          http2HandlerCtx,
          headers.streamId,
          headers.headers(),
          headers.padding,
          headers.isEndStream,
          promise
        ); ()

      case reset: Http2ResetFrame =>
        http2Handler.resetStream(
          http2HandlerCtx,
          reset.streamId,
          reset.errorCode,
          promise
        ); ()

      case update: Http2WindowUpdateFrame =>
        try {
          http2Handler.connection.local.flowController.consumeBytes(
            http2Handler.connection.stream(update.streamId),
            update.windowSizeIncrement
          )
          promise.setSuccess(); ()
        } catch {
          case e: Throwable => promise.setFailure(e); ()
        }

      case msg => ctx.write(msg, promise); ()
    }

}

object H2FrameCodec {

  /** Aggressively acknowledge window updates */
  val DefaultWindowUpdateRatio = 0.99f // on (0.0, 1.0)

  def client(
    settings: Http2Settings = new Http2Settings,
    windowUpdateRatio: Float = DefaultWindowUpdateRatio,
    autoRefillConnectionWindow: Boolean = false
  ): H2FrameCodec =
    mk(false, settings, windowUpdateRatio, autoRefillConnectionWindow)

  def server(
    settings: Http2Settings = new Http2Settings,
    windowUpdateRatio: Float = DefaultWindowUpdateRatio,
    autoRefillConnectionWindow: Boolean = false
  ): H2FrameCodec =
    mk(true, settings, windowUpdateRatio, autoRefillConnectionWindow)

  private[this] def mk(
    isServer: Boolean,
    settings: Http2Settings,
    updateRatio: Float,
    refillConn: Boolean
  ): H2FrameCodec = {
    require(0.0 < updateRatio && updateRatio < 1.0)

    val conn = new DefaultHttp2Connection(isServer)
    conn.local.flowController(new DefaultHttp2LocalFlowController(conn, updateRatio, refillConn) {
      settings.initialWindowSize match {
        case null =>
        case sz => initialWindowSize(sz)
      }
    })
    conn.remote.flowController(new DefaultHttp2RemoteFlowController(conn))

    val encoder = {
      val fw = new Http2OutboundFrameLogger(new DefaultHttp2FrameWriter, frameLogger)
      new DefaultHttp2ConnectionEncoder(conn, fw)
    }

    val decoder = {
      val fr = new Http2InboundFrameLogger(new DefaultHttp2FrameReader, frameLogger)
      new DefaultHttp2ConnectionDecoder(conn, encoder, fr)
    }
    decoder.frameListener(new FrameListener)

    val handler = new ConnectionHandler(decoder, encoder, settings)
    new H2FrameCodec(handler)
  }

  private[this] lazy val frameLogger = new Http2FrameLogger(io.netty.handler.logging.LogLevel.TRACE, getClass)

  private class ConnectionHandler(
    decoder: Http2ConnectionDecoder,
    encoder: Http2ConnectionEncoder,
    initialSettings: Http2Settings
  ) extends Http2ConnectionHandler(decoder, encoder, initialSettings) {

    override protected def onStreamError(
      ctx: ChannelHandlerContext,
      cause: Throwable,
      exc: Http2Exception.StreamException
    ): Unit =
      try {
        if (connection.stream(exc.streamId) != null) {
          ctx.fireExceptionCaught(exc); ()
        }
      } finally {
        super.onStreamError(ctx, cause, exc)
      }
  }

  private class FrameListener extends Http2FrameAdapter {

    override def onRstStreamRead(ctx: ChannelHandlerContext, id: Int, code: Long): Unit = {
      val rst = new DefaultHttp2ResetFrame(code)
      rst.streamId(id)
      ctx.fireChannelRead(rst); ()
    }

    override def onHeadersRead(
      ctx: ChannelHandlerContext,
      streamId: Int,
      headers: Http2Headers,
      streamDependency: Int,
      weight: Short,
      exclusive: Boolean,
      padding: Int,
      eos: Boolean
    ): Unit = onHeadersRead(ctx, streamId, headers, padding, eos)

    override def onHeadersRead(
      ctx: ChannelHandlerContext,
      streamId: Int,
      headers: Http2Headers,
      padding: Int,
      eos: Boolean
    ): Unit = {
      val hdrs = new DefaultHttp2HeadersFrame(headers, eos, padding)
      hdrs.streamId(streamId)
      ctx.fireChannelRead(hdrs); ()
    }

    override def onDataRead(
      ctx: ChannelHandlerContext,
      streamId: Int,
      content: ByteBuf,
      padding: Int,
      eos: Boolean
    ): Int = {
      val data = new DefaultHttp2DataFrame(content.retain(), eos, padding)
      data.streamId(streamId)
      ctx.fireChannelRead(data)
      0 // bytes are marked as consumed via WindowUpdateFrame writes
    }

  }

}
