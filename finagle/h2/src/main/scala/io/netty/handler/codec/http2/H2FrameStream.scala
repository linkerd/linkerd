package io.netty.handler.codec.http2

case class H2FrameStream(streamId: Int, streamState: Http2Stream.State) extends Http2FrameStream {
  override def state(): Http2Stream.State = streamState

  override def id(): Int = streamId
}

object H2FrameStream {
  def apply(stream: Http2FrameStream): H2FrameStream =
    H2FrameStream(stream.id(), stream.state())
}