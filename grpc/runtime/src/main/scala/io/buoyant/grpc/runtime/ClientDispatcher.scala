package io.buoyant.grpc.runtime

import com.twitter.finagle.buoyant.h2
import com.twitter.finagle.{Failure, FailureFlags, Service => FinagleService}
import com.twitter.util._
import io.buoyant.grpc.runtime.H2Headers.requestHeaders
import io.netty.buffer.Unpooled

object ClientDispatcher {

  private[this] def requestUnary[T](path: String, msg: T, codec: Codec[T]): h2.Request = {
    val buf = codec.encodeGrpcMessage(msg)
    val frame = h2.Frame.Data(buf, eos = true)
    val stream = h2.Stream()
    stream.write(frame) // don't wait
    h2.Request(requestHeaders(path), stream)
  }

  private[this] def requestStreaming[T](path: String, msgs: Stream[T], codec: Codec[T]): h2.Request = {
    val frames = h2.Stream()
    def loop(): Future[Unit] =
      msgs.recv().transform {
        case Return(Stream.Releasable(msg, release)) =>
          val buf = codec.encodeGrpcMessage(msg)
          val frame = h2.Frame.Data(buf, eos = false, release)
          frames.write(frame).before(loop())

        case Throw(s@GrpcStatus.Ok(_)) =>
          frames.write(h2.Frame.Data(Unpooled.EMPTY_BUFFER, eos = true))

        case Throw(s: GrpcStatus) =>
          frames.cancel(s.toReset)
          Future.exception(s)

        case Throw(e) =>
          frames.cancel(h2.Reset.InternalError)
          Future.exception(e)
      }

    loop()
    frames.onCancel.onSuccess { rst =>
      msgs.reset(rst)
    }

    h2.Request(requestHeaders(path), frames)
  }

  private[this] def acceptUnary[T](result: Try[h2.Response], codec: Codec[T]): Future[T] =
    result match {
      case Throw(Failure(Some(e))) => Future.exception(e)
      case Throw(e) => Future.exception(e)
      case Return(rsp) =>
        val f = Codec.bufferGrpcFrame(rsp.stream).map(codec.decodeByteBuffer)

        val p = new Promise[T]
        p.setInterruptHandler {
          case e@Failure(cause) if e.isFlagged(FailureFlags.Interrupted) =>
            val rst = cause match {
              case Some(s: GrpcStatus) => s.toReset
              case _ => h2.Reset.Cancel
            }
            f.raise(rst)
        }
        f.proxyTo(p)
        p
    }

  private[this] def acceptStreaming[T](rspF: Future[h2.Response], codec: Codec[T]): Stream[T] =
    Stream.deferred(rspF.map(codec.decodeResponse))

  object Rpc {

    case class UnaryToUnary[Req, Rsp](
      client: FinagleService[h2.Request, h2.Response],
      path: String,
      reqCodec: Codec[Req],
      rspCodec: Codec[Rsp]
    ) {
      private[this] val respond: Try[h2.Response] => Future[Rsp] = acceptUnary(_, rspCodec)
      def apply(msg: Req): Future[Rsp] = {
        val req = requestUnary(path, msg, reqCodec)
        client(req).transform(respond)
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
      private[this] val respond: Try[h2.Response] => Future[Rsp] = acceptUnary(_, rspCodec)
      def apply(msgs: Stream[Req]): Future[Rsp] = {
        val req = requestStreaming(path, msgs, reqCodec)
        client(req).transform(respond)
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
