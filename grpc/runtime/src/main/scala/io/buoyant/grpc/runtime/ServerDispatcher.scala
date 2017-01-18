package io.buoyant.grpc.runtime

import com.twitter.finagle.{Service => FinagleService}
import com.twitter.finagle.buoyant.h2
import com.twitter.io.Buf
import com.twitter.util.{Future, Return, Throw}
import io.buoyant.grpc.GrpcError

object ServerDispatcher {

  trait Service {
    def name: String
    def rpcs: Seq[Rpc]
  }

  trait Rpc extends FinagleService[h2.Request, h2.Response] {
    def name: String
  }

  object Rpc {

    class UnaryToUnary[Req, Rsp](
      val name: String,
      serve: Req => Future[Rsp],
      reqCodec: Codec[Req],
      rspCodec: Codec[Rsp]
    ) extends ServerDispatcher.Rpc {

      override def apply(req: h2.Request): Future[h2.Response] =
        acceptUnary(reqCodec, req).flatMap(serve).map(respond)

      private[this] val respond: Rsp => h2.Response = respondUnary(rspCodec, _)
    }

    class UnaryToStream[Req, Rsp](
      val name: String,
      serve: Req => Stream[Rsp],
      reqCodec: Codec[Req],
      rspCodec: Codec[Rsp]
    ) extends ServerDispatcher.Rpc {

      override def apply(req: h2.Request): Future[h2.Response] =
        acceptUnary(reqCodec, req).map(streamResponse)

      private[this] val streamResponse: Req => h2.Response =
        req => respondStreaming(rspCodec, serve(req))
    }

    class StreamToUnary[Req, Rsp](
      val name: String,
      serve: Stream[Req] => Future[Rsp],
      reqCodec: Codec[Req],
      rspCodec: Codec[Rsp]
    ) extends ServerDispatcher.Rpc {

      override def apply(req: h2.Request): Future[h2.Response] =
        serve(acceptStreaming(reqCodec, req)).map(respond)

      private[this] val respond: Rsp => h2.Response = respondUnary(rspCodec, _)
    }

    class StreamToStream[Req, Rsp](
      val name: String,
      serve: Stream[Req] => Stream[Rsp],
      reqCodec: Codec[Req],
      rspCodec: Codec[Rsp]
    ) extends ServerDispatcher.Rpc {

      override def apply(req: h2.Request): Future[h2.Response] = {
        val reqs = acceptStreaming(reqCodec, req)
        val rsp = respondStreaming(rspCodec, serve(reqs))
        Future.value(rsp)
      }
    }

    private[this] def acceptUnary[Req](codec: Codec[Req], req: h2.Request): Future[Req] =
      Codec.bufferGrpcFrame(req.stream)
        .map(codec.decodeBuf)

    private[this] def acceptStreaming[Req](codec: Codec[Req], req: h2.Request): Stream[Req] = {
      val frames = req.stream
      val msgs = Stream[Req]()
      def loop(): Future[Unit] =
        frames.read().transform {
          case Return(data: h2.Frame.Data) =>
            // TODO proper framing
            val sendF = data.buf match {
              case Buf.Empty => Future.Unit
              case buf =>
                val msg = codec.decodeGrpcMessage(data.buf)
                // XXX Should the release be passed down with the message?
                // Should the release API be changed to allow partial
                // release (i.e. of encoded messages within an h2 frame)?
                msgs.send(msg)
            }
            val writeF = sendF.before(data.release())
            if (data.isEnd) writeF.before(msgs.close())
            else writeF.before(loop())

          case Return(data: h2.Frame.Trailers) =>
            // Odd, but let's roll with this...
            msgs.close()

          case Throw(rst: h2.Reset) =>
            msgs.reset(GrpcError.fromRst(rst))
          case Throw(e) =>
            msgs.close() // TODO reset the stream with a failure.
        }
      loop() // TODO detect interrupt and cancel?
      msgs
    }

    private[this] def respondUnary[Rsp](codec: Codec[Rsp], msg: Rsp): h2.Response = {
      val buf = codec.encodeGrpcMessage(msg)
      val stream = h2.Stream()
      stream.write(h2.Frame.Data(buf, eos = false))
        .before(stream.write(h2.Frame.Trailers(
          "grpc-status" -> "0"
        )))
      h2.Response(h2.Headers(h2.Headers.Status -> "200", h2.Headers.ContentType -> "application/grpc+proto"), stream)
    }

    private[this] def respondStreaming[Rsp](codec: Codec[Rsp], msgs: Stream[Rsp]): h2.Response = {
      val frames = h2.Stream()
      def loop(): Future[Unit] =
        msgs.recv().transform {
          case Return(Stream.Releasable(s, release)) =>
            val buf = codec.encodeGrpcMessage(s)
            val data = h2.Frame.Data(buf, eos = false, release)
            frames.write(data).before(loop())

          case Throw(rejected: Stream.RejectedWithReason) =>
            frames.write(h2.Frame.Trailers("grpc-status" -> rejected.reason.errorCode.toString(), "grpc-message" -> rejected.reason.toString))
              .onSuccess(_ => frames.close())

          case Throw(e) =>
            // TODO: does `grpc-message` have a length restriction?
            // NB: I changed 0 to 2 because if we're catching a Throwable then this must be an error. Yes?
            System.out.println("returning grpc-status 2")
            frames.write(h2.Frame.Trailers("grpc-status" -> "2", "grpc-message" -> e.toString))
              .onSuccess(_ => frames.close())
        }

      loop() // TODO detect tx interrupt and cancel?
      h2.Response(h2.Status.Ok, frames)
    }
  }

  private def fail(status: h2.Status): Future[h2.Response] =
    Future.value(h2.Response(status, h2.Stream.empty()))

  def apply(hd: Service, tl: Service*): ServerDispatcher =
    new ServerDispatcher(hd +: tl)
}

/** Dispatches requests to an arbitrary list of grpc services */
class ServerDispatcher(services: Seq[ServerDispatcher.Service])
  extends FinagleService[h2.Request, h2.Response] {

  private[this] val rpcByPath: Map[String, ServerDispatcher.Rpc] =
    services.flatMap { svc =>
      svc.rpcs.map { rpc =>
        s"/${svc.name}/${rpc.name}" -> rpc
      }
    }.toMap

  override def apply(req: h2.Request): Future[h2.Response] =
    req.method match {
      case h2.Method.Post =>
        rpcByPath.get(req.path) match {
          case Some(dispatch) => dispatch(req)
          case None => ServerDispatcher.fail(h2.Status.NotFound)
        }
      case _ => ServerDispatcher.fail(h2.Status.MethodNotAllowed)
    }
}
