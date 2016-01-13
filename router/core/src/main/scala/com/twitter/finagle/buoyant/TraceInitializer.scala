package com.twitter.finagle.buoyant

import com.twitter.finagle.{ServiceFactory, Stackable}
import com.twitter.finagle.tracing.TraceInitializerFilter

object TraceInitializer {
  val role = TraceInitializerFilter.role

  def server[Req, Rsp]: Stackable[ServiceFactory[Req, Rsp]] =
    new TraceInitializerFilter.Module[Req, Rsp](newId = true)

  def client[Req, Rsp]: Stackable[ServiceFactory[Req, Rsp]] =
    new TraceInitializerFilter.Module[Req, Rsp](newId = false)

}
