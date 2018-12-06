package com.twitter.finagle.buoyant.linkerd

import com.twitter.finagle.Thrift.param.{AttemptTTwitterUpgrade, ProtocolFactory}
import com.twitter.finagle._
import com.twitter.finagle.client.StackClient
import com.twitter.finagle.filter.PayloadSizeFilter
import com.twitter.finagle.param.Label
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.thrift._
import com.twitter.finagle.thrift.thrift.ConnectionOptions
import com.twitter.util.{Future, Stopwatch}
import org.apache.thrift.protocol.{TBinaryProtocol, TMessage, TMessageType, TProtocolFactory}
import org.apache.thrift.transport.TMemoryInputTransport

object ThriftClientPrep {

  val role = StackClient.Role.prepConn

  val module = new Stack.ModuleParams[ServiceFactory[ThriftClientRequest, Array[Byte]]] {
    override def parameters: Seq[Stack.Param[_]] = Nil
    override val role: Stack.Role = ThriftClientPrep.role
    override val description = "Prepare TTwitter thrift connection"
    def make(
      params: Stack.Params,
      next: ServiceFactory[ThriftClientRequest, Array[Byte]]
    ): ServiceFactory[ThriftClientRequest, Array[Byte]] = {

      val param.Stats(stats) = params[param.Stats]
      val Thrift.param.AttemptTTwitterUpgrade(attemptUpgrade) =
        params[Thrift.param.AttemptTTwitterUpgrade]
      val Label(label) = params[Label]
      val Thrift.param.ClientId(clientId) = params[Thrift.param.ClientId]
      val Thrift.param.ProtocolFactory(pf) = params[Thrift.param.ProtocolFactory]
      prepare(next, stats, attemptUpgrade, label, clientId, pf)
    }
  }

  private def prepareService(
    stats: StatsReceiver,
    attemptUpgrade: Boolean,
    label: String,
    clientId: Option[ClientId],
    pf: TProtocolFactory
  )(
    service: Service[ThriftClientRequest, Array[Byte]]
  ): Future[Service[ThriftClientRequest, Array[Byte]]] = {
    val payloadSize = new PayloadSizeFilter[ThriftClientRequest, Array[Byte]](
      stats,
      PayloadSizeFilter.ClientReqTraceKey,
      PayloadSizeFilter.ClientRepTraceKey,
      _.message.length,
      _.length
    )
    val payloadSizeService = payloadSize.andThen(service)
    val upgradedService =
      if (attemptUpgrade) {
        upgrade(payloadSizeService, label, clientId, pf)
      } else {
        Future.value(payloadSizeService)
      }

    upgradedService.map { upgraded =>
      new ValidateThriftService(upgraded, pf)
    }
  }

  def prepare(
    underlying: ServiceFactory[ThriftClientRequest, Array[Byte]],
    stats: StatsReceiver,
    attemptUpgrade: Boolean,
    label: String,
    clientId: Option[ClientId],
    pf: TProtocolFactory
  ): ServiceFactory[ThriftClientRequest, Array[Byte]] = {
    val preparingFactory = underlying.flatMap(prepareService(stats, attemptUpgrade, label, clientId, pf))

    if (attemptUpgrade) {
      new ServiceFactoryProxy(preparingFactory) {
        val stat = stats.stat("codec_connection_preparation_latency_ms")
        override def apply(conn: ClientConnection) = {
          val elapsed = Stopwatch.start()
          super.apply(conn).ensure {
            stat.add(elapsed().inMilliseconds)
          }
        }
      }
    } else {
      preparingFactory
    }
  }

  private def upgrade(
    service: Service[ThriftClientRequest, Array[Byte]],
    label: String,
    clientId: Option[ClientId],
    pf: TProtocolFactory
  ): Future[Service[ThriftClientRequest, Array[Byte]]] = {
    // Attempt to upgrade the protocol the first time around by
    // sending a magic method invocation.
    val buffer = new OutputBuffer(pf)
    buffer().writeMessageBegin(
      new TMessage(ThriftTracing.CanTraceMethodName, TMessageType.CALL, 0)
    )

    val options = new ConnectionOptions
    options.write(buffer())

    buffer().writeMessageEnd()

    service(new ThriftClientRequest(buffer.toArray, false)).map { bytes =>
      val memoryTransport = new TMemoryInputTransport(bytes)
      val iprot = pf.getProtocol(memoryTransport)
      val reply = iprot.readMessageBegin()

      val ttwitter = new TTwitterClientFilter(
        label,
        reply.`type` != TMessageType.EXCEPTION,
        clientId, pf
      )
      // TODO: also apply this for Protocols.binaryFactory

      val seqIdFilter =
        if (pf.isInstanceOf[TBinaryProtocol.Factory])
          new SeqIdFilter
        else
          Filter.identity[ThriftClientRequest, Array[Byte]]

      seqIdFilter.andThen(ttwitter).andThen(service)
    }
  }

}
