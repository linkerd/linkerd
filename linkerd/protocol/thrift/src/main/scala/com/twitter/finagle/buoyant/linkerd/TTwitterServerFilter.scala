package com.twitter.finagle.buoyant.linkerd

import com.twitter.finagle.context.Contexts
import com.twitter.finagle.thrift._
import com.twitter.finagle.tracing.Trace
import com.twitter.finagle.util.ByteArrays
import com.twitter.finagle.{Dtab, Service, SimpleFilter}
import com.twitter.io.Buf
import com.twitter.util.Future
import io.buoyant.router.thrift.Dest
import org.apache.thrift.protocol.{TMessage, TMessageType, TProtocolFactory}
import scala.jdk.CollectionConverters._

/**
 * Forked from https://github.com/twitter/finagle/blob/develop/finagle-thrift/src/main/scala/com/twitter/finagle/thrift/TTwitterServerFilter.scala
 * We have modified TTwitterServerFilter to read the dest field out of the request header and write
 * it into the Dest local context.
 */
class TTwitterServerFilter(
  serviceName: String,
  protocolFactory: TProtocolFactory
) extends SimpleFilter[Array[Byte], Array[Byte]] {
  // Concurrency is not an issue here since we have an instance per
  // channel, and receive only one request at a time (thrift does no
  // pipelining).  Furthermore, finagle will guarantee this by
  // serializing requests. There are no guarantees about thread-pinning
  // however.
  private[this] var isUpgraded = false

  private[this] lazy val successfulUpgradeReply = Future {
    val buffer = new OutputBuffer(protocolFactory)
    buffer().writeMessageBegin(
      new TMessage(ThriftTracing.CanTraceMethodName, TMessageType.REPLY, 0)
    )
    val upgradeReply = new thrift.UpgradeReply
    upgradeReply.write(buffer())
    buffer().writeMessageEnd()

    // Note: currently there are no options, so there's no need
    // to parse them out.
    buffer.toArray
  }

  def apply(
    request: Array[Byte],
    service: Service[Array[Byte], Array[Byte]]
  ): Future[Array[Byte]] = {
    // What to do on exceptions here?
    if (isUpgraded) {
      val header = new thrift.RequestHeader
      val request_ = InputBuffer.peelMessage(request, header, protocolFactory)
      val richHeader = new RichRequestHeader(header)

      // Set the TraceId. This will be overwritten by TraceContext, if it is
      // loaded, but it should never be the case that the ids from the two
      // paths won't match.
      Trace.letId(richHeader.traceId) {

        // Write dest into a local context
        Dest.local = richHeader.dest

        Dtab.local ++= richHeader.dtab

        // If `header.client_id` field is non-null, then allow it to take
        // precedence over an id potentially provided by in the key-value pairs
        // when performing tracing.
        ClientId.let(richHeader.clientId) {
          Trace.recordBinary("srv/thrift/clientId", ClientId.current.getOrElse("None"))

          // Load the values from the received context into the broadcast context
          val ctxKeys: Iterable[(Buf, Buf)] = {
            if (header.contexts == null) Iterable.empty
            else {
              header.contexts.asScala.map { c =>
                Buf.ByteArray.Owned(c.getKey()) -> Buf.ByteArray.Owned(c.getValue())
              }
            }
          }

          Contexts.broadcast.letUnmarshal(ctxKeys) {
            service(request_).map {
              case response if response.isEmpty => response
              case response =>
                val responseHeader = new thrift.ResponseHeader
                ByteArrays.concat(
                  OutputBuffer.messageToArray(responseHeader, protocolFactory),
                  response
                )
            }
          }
        }
      }
    } else {
      val buffer = new InputBuffer(request, protocolFactory)
      val msg = buffer().readMessageBegin()

      // TODO: only try once?
      if (msg.`type` == TMessageType.CALL &&
        msg.name == ThriftTracing.CanTraceMethodName) {

        val connectionOptions = new thrift.ConnectionOptions
        connectionOptions.read(buffer())

        // upgrade & reply.
        isUpgraded = true
        successfulUpgradeReply
      } else {
        Trace.recordBinary("srv/thrift/ttwitter", false)
        service(request)
      }
    }
  }
}
