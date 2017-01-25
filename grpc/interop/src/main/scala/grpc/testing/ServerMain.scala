package grpc.testing

import com.google.protobuf.CodedOutputStream
import com.twitter.conversions.time._
import com.twitter.finagle.buoyant.H2
import com.twitter.io.Buf
import com.twitter.util.{Await, Future, Promise, Return, Throw}
import io.buoyant.grpc.runtime.{Stream, ServerDispatcher}
import java.nio.ByteBuffer
import java.util.Arrays

/**
 * The main object for running a gRPC interop server.
 *
 * The interop tests are described here:
 *   https://github.com/grpc/grpc/blob/master/doc/interop-test-descriptions.md
 */
object ServerMain {
  def main(args: Array[String]) {

    val iface = new TestService {
      def emptyCall(empty: Empty): Future[Empty] = {
        Future.value(new Empty())
      }

      def unaryCall(req: SimpleRequest): Future[SimpleResponse] = {
        Future.value(new SimpleResponse(None, None, None))
      }

      def cacheableUnaryCall(req: SimpleRequest): Future[SimpleResponse] = {
        Future.value(new SimpleResponse(None, None, None))
      }

      /**
       * Echo back each request with a Payload having the requested size
       */
      def fullDuplexCall(reqs: Stream[StreamingOutputCallRequest]): Stream[StreamingOutputCallResponse] = {
        val rsps = Stream[StreamingOutputCallResponse]
        def process(): Future[Unit] = reqs.recv().transform {
          case Throw(Stream.Closed) => rsps.close()
          case Throw(e) => Future.exception(e)
          case Return(req) =>
            req.value.payload.flatMap(_.body) match {
              case None => rsps.close()
              case Some(body) =>
                val chars = Array.fill(body.length) { 0.toByte } // {} specializes us to a Byte array
                val toSend = Buf.ByteArray.Owned(chars)
                val msg = StreamingOutputCallResponse(Some(Payload(None, Some(toSend))))
                rsps.send(msg).before(process())
            }
        }
        process()

        rsps
      }

      // TODO: if an interop test can be found that needs this, we will implement it.
      def halfDuplexCall(req: Stream[StreamingOutputCallRequest]): Stream[StreamingOutputCallResponse] = ???

      /**
       * Returns the aggregated size of input payloads.
       */
      def streamingInputCall(reqs: Stream[StreamingInputCallRequest]): Future[StreamingInputCallResponse] = {
        def process(processed: Int): Future[Int] = reqs.recv().transform {
          case Throw(Stream.Closed) => Future.value(processed)
          case Throw(e) => Future.exception(e)
          case Return(req) =>
            val sz = req.value.payload.flatMap(_.body).map(_.length).getOrElse(0)
            process(processed + sz)
        }

        process(0).map { sz => StreamingInputCallResponse(Some(sz)) }
      }

      /**
       * For each ResponseParameter sent, we return a frame in the stream with the requested size.
       */
      def streamingOutputCall(req: StreamingOutputCallRequest): Stream[StreamingOutputCallResponse] = {
        val rsps = Stream[StreamingOutputCallResponse]()

        def process(params: Seq[ResponseParameters]): Future[Unit] = params match {
          case Seq(param, tail@_*) =>
            val size = param.size.getOrElse(0)
            val chars = Array.fill(size) { 0.toByte } // {} specializes us to a Byte array
            val toSend = Buf.ByteArray.Owned(chars)
            val msg = StreamingOutputCallResponse(Some(Payload(None, Some(toSend))))
            rsps.send(msg).before(process(tail))

          case _ => rsps.close()
        }
        process(req.responseparameters)

        rsps
      }

      // This method should not be implemented on the Server.
      // Note: making it unimplemented with ??? is still an implementation.
      //def unimplementedCall(req: Empty): Future[grpc.testing.Empty] = ???
    }

    val service = new ServerDispatcher(Seq(new TestService.Server(iface)))
    val server = H2.serve(":60001", service)
    val _ = Await.ready(server)
  }
}
