package com.twitter.finagle.buoyant.http2

import com.twitter.finagle.netty4.{BufAsByteBuf, ByteBufAsBuf}
import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.finagle.transport.{Transport, TransportProxy}
import com.twitter.io.Buf
import com.twitter.util.{Future, Stopwatch, Time}
import io.netty.handler.codec.http2._

object Http2Transport {
  trait Writer {
    def writeHeaders(orig: Headers, id: Int, eos: Boolean = false): Future[Unit]
    def writeTrailers(orig: Headers, id: Int): Future[Unit]
    def writeTrailers(tlrs: DataStream.Trailers, id: Int): Future[Unit]

    def write(v: DataStream.Value, id: Int): Future[Unit]
    def writeData(buf: Buf, eos: Boolean = false, id: Int): Future[Unit]
    def writeData(data: DataStream.Data, id: Int): Future[Unit]
    def writeWindowUpdate(incr: Int, id: Int): Future[Unit]

  }
}

class Http2Transport(
  transport: Transport[Http2StreamFrame, Http2StreamFrame],
  statsReceiver: StatsReceiver = NullStatsReceiver
) extends TransportProxy[Http2StreamFrame, Http2StreamFrame](transport)
  with Http2Transport.Writer {

  // private[this] val log = com.twitter.logging.Logger.get(getClass.getName)

  private[this] val transportReadUs = statsReceiver.stat("read_us")
  private[this] val transportWriteUs = statsReceiver.stat("write_us")

  override def read(): Future[Http2StreamFrame] = {
    val snap = Stopwatch.start()
    val readF = transport.read()
    // readF.respond(log.info("read: %s", _))
    readF.onSuccess(_ => transportReadUs.add(snap().inMicroseconds))
    readF
  }

  override def write(f: Http2StreamFrame): Future[Unit] = {
    val snap = Stopwatch.start()
    val writeF = transport.write(f)
    // writeF.respond(log.info("write: %s => %s", f, _))
    writeF.onSuccess(_ => transportWriteUs.add(snap().inMicroseconds))
    writeF
  }

  def writeHeaders(orig: Headers, id: Int, eos: Boolean = false): Future[Unit] = {
    val headers = orig match {
      case h: Netty4Headers => h.underlying
      case hs =>
        val headers = new DefaultHttp2Headers
        for ((k, v) <- hs.toSeq) headers.add(k, v)
        headers
    }
    val frame = new DefaultHttp2HeadersFrame(headers, eos)
    if (id >= 0) frame.setStreamId(id)
    write(frame)
  }

  def writeTrailers(headers: Headers, id: Int): Future[Unit] =
    writeHeaders(headers, id, eos = true)

  def writeTrailers(tlrs: DataStream.Trailers, id: Int): Future[Unit] =
    writeTrailers(tlrs.headers, id)

  def write(v: DataStream.Value, id: Int): Future[Unit] = v match {
    case data: DataStream.Data => writeData(data.buf, data.isEnd, id)
    case DataStream.Trailers(tlrs) => writeTrailers(tlrs, id)
  }

  def writeData(buf: Buf, eos: Boolean = false, id: Int): Future[Unit] = {
    val bb = BufAsByteBuf.Owned(buf)
    val frame = new DefaultHttp2DataFrame(bb, eos)
    if (id >= 0) frame.setStreamId(id)
    write(frame)
  }

  def writeData(data: DataStream.Data, id: Int): Future[Unit] = {
    val bb = BufAsByteBuf.Owned(data.buf)
    val frame = new DefaultHttp2DataFrame(bb, data.isEnd)
    if (id >= 0) frame.setStreamId(id)
    write(frame)
  }

  def writeWindowUpdate(incr: Int, id: Int): Future[Unit] = {
    val frame = new DefaultHttp2WindowUpdateFrame(incr)
    if (id >= 0) frame.setStreamId(id)
    write(frame)
  }
}
