package io.buoyant.grpc.runtime

import com.twitter.finagle.{Service => FinagleService}
import com.twitter.finagle.buoyant.h2
import com.twitter.io.Buf
import com.twitter.util.{Future, Return, Throw}

object ClientDispatcher {

  def requestUnary[T](path: String, msg: T, codec: Codec[T]): h2.Request = {
    val buf = codec.encodeGrpcMessage(msg)
    val frame = h2.Frame.Data(buf, eos = true)
    val stream = h2.Stream()
    stream.write(frame) // don't wait
    h2.Request("http", h2.Method.Post, "", path, stream)
  }

  def requestStreaming[T](path: String, msgs: Stream[T], codec: Codec[T]): h2.Request = {
    val stream = h2.Stream()
    def loop(): Future[Unit] =
      msgs.recv().transform {
        case Return(Stream.Releasable(msg, release)) =>
          val buf = codec.encodeGrpcMessage(msg)
          val frame = h2.Frame.Data(buf, eos = false, release)
          stream.write(frame).before(loop())

        case Throw(Stream.Closed) =>
          val frame = h2.Frame.Data(Buf.Empty, eos = true)
          stream.write(frame)

        case Throw(e) =>
          // TODO better
          stream.reset(h2.Reset.InternalError)
          Future.exception(e)
      }
    loop()
    h2.Request("http", h2.Method.Post, "", path, stream)
  }

  def acceptUnary[T](rsp: h2.Response, codec: Codec[T]): Future[T] =
    Codec.bufferGrpcFrame(rsp.stream).map(codec.decodeBuf)

  def acceptStreaming[T](rspF: Future[h2.Response], codec: Codec[T]): Stream[T] =
    Stream.deferred(rspF.map(codec.decodeResponse))

  object Rpc {

    case class UnaryToUnary[Req, Rsp](
      client: FinagleService[h2.Request, h2.Response],
      path: String,
      reqCodec: Codec[Req],
      rspCodec: Codec[Rsp]
    ) {
      private[this] val respond: h2.Response => Future[Rsp] = acceptUnary(_, rspCodec)
      def apply(msg: Req): Future[Rsp] = {
        val req = requestUnary(path, msg, reqCodec)
        client(req).flatMap(respond)
      }
    }

    case class UnaryToStream[Req, Rsp](
      client: FinagleService[h2.Request, h2.Response],
      path: String,
      reqCodec: Codec[Req],
      rspCodec: Codec[Rsp]
    ) {
      def apply(msg: Req): Stream[Rsp] = {
        val req = requestUnary(path, msg, reqCodec)
        acceptStreaming(client(req), rspCodec)
      }
    }

    case class StreamToUnary[Req, Rsp](
      client: FinagleService[h2.Request, h2.Response],
      path: String,
      reqCodec: Codec[Req],
      rspCodec: Codec[Rsp]
    ) {
      private[this] val respond: h2.Response => Future[Rsp] = acceptUnary(_, rspCodec)
      def apply(msgs: Stream[Req]): Future[Rsp] = {
        val req = requestStreaming(path, msgs, reqCodec)
        client(req).flatMap(respond)
      }
    }

    case class StreamToStream[Req, Rsp](
      client: FinagleService[h2.Request, h2.Response],
      path: String,
      reqCodec: Codec[Req],
      rspCodec: Codec[Rsp]
    ) {
      def apply(msgs: Stream[Req]): Stream[Rsp] = {
        val req = requestStreaming(path, msgs, reqCodec)
        acceptStreaming(client(req), rspCodec)
      }
    }
  }
}
