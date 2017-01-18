package grpc.testing

import com.google.protobuf.CodedOutputStream
import com.twitter.conversions.time._
import com.twitter.finagle.buoyant.H2
import com.twitter.io.Buf
import com.twitter.util.{Await, Future, Promise, Throw}
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

      def fullDuplexCall(req: Stream[StreamingOutputCallRequest]): Stream[StreamingOutputCallResponse] = {
        val tx = Stream[StreamingOutputCallResponse]

        req.recv().map { outputCallRequest =>
          outputCallRequest.value.payload.map { payload =>
            payload.body.map { body =>
              val toSend = Buf.Utf8("".padTo(body.length, "\u0000").toString())
              tx.send(StreamingOutputCallResponse(Some(Payload(None, Some(toSend)))))
            }
          }
        }

        return tx
      }

      def halfDuplexCall(req: Stream[StreamingOutputCallRequest]): Stream[StreamingOutputCallResponse] = {
        val tx = Stream[StreamingOutputCallResponse]
        tx.send(StreamingOutputCallResponse(None))
        return tx
      }

      def streamingInputCall(req: Stream[StreamingInputCallRequest]): Future[StreamingInputCallResponse] = {
        // expected: aggregated_payload_size: 74922
        // For each item in req, add the payload.body.length to the aggregated_payload_size
        var size = 0
        val stream = req.recv()
        // TODO: ask oliver if this is how he intended for this API to work.
        stream.map { inputCallRequest =>
          inputCallRequest.value.payload.map { _.body.map { body => size += body.length } }
        }
        Future.value(StreamingInputCallResponse(Some(size)))
      }

      def streamingOutputCall(req: StreamingOutputCallRequest): Stream[StreamingOutputCallResponse] = {
        val tx = Stream[StreamingOutputCallResponse]()
        tx.send(StreamingOutputCallResponse(None))
        return tx
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
