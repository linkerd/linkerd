package com.twitter.finagle.buoyant

import com.twitter.finagle.{ServiceFactory, Stack, mux}
import com.twitter.finagle.tracing.TraceInitializerFilter

object MuxTraceInitializer {
  val role = TraceInitializerFilter.role

  /** Always generate trace ids on the server side because that's how we roll. */
  object server extends TraceInitializerFilter.Module[mux.Request, mux.Response](newId = true)
}
