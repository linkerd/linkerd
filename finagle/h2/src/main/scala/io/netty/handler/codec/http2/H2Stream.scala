package io.netty.handler.codec.http2

case class H2Stream(streamId: Int, streamState: Http2Stream.State) extends Http2Stream {

  override def headersReceived(isInformational: Boolean): Http2Stream = ???

  override def isPushPromiseSent: Boolean = ???

  override def isTrailersSent: Boolean = ???

  override def state(): Http2Stream.State = streamState

  override def id(): Int = streamId

  override def close(): Http2Stream = ???

  override def isHeadersSent: Boolean = ???

  override def isResetSent: Boolean = ???

  override def headersSent(isInformational: Boolean): Http2Stream = ???

  override def getProperty[V](key: Http2Connection.PropertyKey): V = ???

  override def pushPromiseSent(): Http2Stream = ???

  override def resetSent(): Http2Stream = ???

  override def isTrailersReceived: Boolean = ???

  override def closeLocalSide(): Http2Stream = ???

  override def closeRemoteSide(): Http2Stream = ???

  override def setProperty[V](key: Http2Connection.PropertyKey, value: V): V = ???

  override def isHeadersReceived: Boolean = ???

  override def removeProperty[V](key: Http2Connection.PropertyKey): V = ???

  override def open(halfClosed: Boolean): Http2Stream = ???
}
