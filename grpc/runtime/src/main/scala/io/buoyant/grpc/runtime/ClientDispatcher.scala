package io.buoyant.grpc.runtime

import com.twitter.finagle.{Service => FinagleService}
import com.twitter.finagle.buoyant.h2
import com.twitter.io.Buf
import com.twitter.util.{Future, Return, Throw}
import io.buoyant.grpc.GrpcError

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
        case Throw(rst: h2.Reset) =>
          stream.reset(GrpcError.toRst(GrpcError.fromRst(rst)))
          Future.exception(rst)
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

  def acceptStreaming[T](rspF: Future[h2.Response], codec: Codec[T]): Stream[T] = {
    val stream = Stream[T]()
    rspF.respond {
      case Return(rsp) =>
        def loop(): Future[Unit] = {
          rsp.stream.read().transform {
            case Return(trailers: h2.Frame.Trailers) =>
              // TODO check grpc-status
              trailers.release().before(stream.close())

            case Return(data: h2.Frame.Data) =>
              // TODO framing/buffering
              val msg = codec.decodeBuf(Codec.decodeGrpcFrame(data.buf))
              stream.send(msg).before(data.release()).before(loop())

            case Throw(e) =>
              // TODO reset
              stream.close().before(Future.exception(e))
          }
        }
        val _ = loop()

      case Throw(e) =>
        val _ = stream.close() // TODO reset
    }
    stream
  }

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
