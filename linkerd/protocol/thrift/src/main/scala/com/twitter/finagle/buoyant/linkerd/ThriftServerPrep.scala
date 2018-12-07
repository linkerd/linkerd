package com.twitter.finagle.buoyant.linkerd

import com.twitter.finagle.client.StackClient
import com.twitter.finagle.filter.PayloadSizeFilter
import com.twitter.finagle.param.Label
import com.twitter.finagle.thrift.UncaughtAppExceptionFilter
import com.twitter.finagle.{ServiceFactory, Stack, Thrift, param}

object ThriftServerPrep {
  val role: Stack.Role = StackClient.Role.prepConn

  val module = new Stack.ModuleParams[ServiceFactory[Array[Byte], Array[Byte]]] {
    override def parameters: Seq[Stack.Param[_]] = Nil

    override val role: Stack.Role = ThriftServerPrep.role
    override val description = "Prepare TTwitter thrift connection"

    def make(
      params: Stack.Params,
      next: ServiceFactory[Array[Byte], Array[Byte]]
    ): ServiceFactory[Array[Byte], Array[Byte]] = {
      val Label(label) = params[Label]
      val Thrift.param.ProtocolFactory(pf) = params[Thrift.param.ProtocolFactory]
      val uncaughtExceptionsFilter = new UncaughtAppExceptionFilter(pf)

      next.map { service =>
        val payloadSize = new PayloadSizeFilter[Array[Byte], Array[Byte]](
          params[param.Stats].statsReceiver,
          PayloadSizeFilter.ClientReqTraceKey,
          PayloadSizeFilter.ClientRepTraceKey,
          _.length,
          _.length
        )
        val ttwitter = new TTwitterServerFilter(label, pf)
        payloadSize.andThen(ttwitter).andThen(uncaughtExceptionsFilter).andThen(service)
      }
    }
  }
}
