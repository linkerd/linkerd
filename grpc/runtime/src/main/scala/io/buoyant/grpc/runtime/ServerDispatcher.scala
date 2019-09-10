package io.buoyant.grpc.runtime

import com.twitter.finagle.{Failure, Service => FinagleService}
import com.twitter.finagle.buoyant.h2
import com.twitter.util.{Future, Return, Throw, Try}
import io.buoyant.grpc.runtime.H2Headers.responseHeaders

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
        acceptUnary(reqCodec, req).flatMap(serve).transform(respond)

      private[this] val respond: Try[Rsp] => Future[h2.Response] =
        rsp => Future.value(respondUnary(rspCodec, rsp))
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
        serve(acceptStreaming(reqCodec, req)).transform(_respond)

      private[this] val _respond: Try[Rsp] => Future[h2.Response] =
        rsp => Future.value(respondUnary(rspCodec, rsp))
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
      Codec.bufferWithStatus(req.stream).map {
        case (buf, _) => codec.decodeByteBuffer(Codec.decodeGrpcFrame(buf))
      }

    private[this] def acceptStreaming[Req](codec: Codec[Req], req: h2.Request): Stream[Req] =
      codec.decodeRequest(req)

    private[this] def respondUnary[Rsp](codec: Codec[Rsp], rsp: Try[Rsp]): h2.Response = rsp match {
      case Return(msg) =>
        val buf = codec.encodeGrpcMessage(msg)
        val frames = h2.Stream()
        frames.write(h2.Frame.Data(buf, eos = false))
          .before(frames.write(GrpcStatus.Ok().toTrailers))
        h2.Response(responseHeaders(), frames)

      case Throw(e) =>
        val status = e match {
          case s: GrpcStatus => s
          case e => GrpcStatus.Internal(e.getMessage)
        }
        val frames = h2.Stream()
        frames.write(status.toTrailers)
        h2.Response(responseHeaders(), frames)
    }

    private[this] def respondStreaming[Rsp](codec: Codec[Rsp], msgs: Stream[Rsp]): h2.Response = {
      val frames = h2.Stream()
      def loop(): Future[Unit] =
        msgs.recv().transform {
          case Return(Stream.Releasable(s, release)) =>
            val buf = codec.encodeGrpcMessage(s)
            val data = h2.Frame.Data(buf, eos = false, release)
            frames.write(data).before(loop())

          case Throw(e) =>
            val status = e match {
              case s: GrpcStatus => s
              case e => GrpcStatus.Internal(e.getMessage)
            }
            frames.write(status.toTrailers)
        }

      val loopF = loop()

      // If the client cancels the response, proactively reset the
      // server's stream.
      frames.onCancel.onSuccess { rst =>
        msgs.reset(rst)
      }

      h2.Response(responseHeaders(), frames)
    }
  }

  private def fail(status: GrpcStatus): Future[h2.Response] = {
    val stream = h2.Stream.const(status.toTrailers)
    Future.value(h2.Response(responseHeaders(h2.Status.BadRequest), stream))
  }

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

  override def apply(req: h2.Request): Future[h2.Response] = {
    // According to gRPC spec (https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md)
    // server must return 415 if content-type is not set to application/grpc to allow for graceful
    // error handling when plain H2 client makes request to a gRPC endpoint.
    // This, however, would be a breaking change on linkerd side.
    // https://github.com/linkerd/linkerd/issues/2326
    req.method match {
      case h2.Method.Post =>
        rpcByPath.get(req.path) match {
          case Some(dispatch) => dispatch(req)
          case None => ServerDispatcher.fail(GrpcStatus.Unimplemented(req.path))
        }
      case method => ServerDispatcher.fail(GrpcStatus.Unknown(s"unsupported method: $method"))
    }
  }
}
