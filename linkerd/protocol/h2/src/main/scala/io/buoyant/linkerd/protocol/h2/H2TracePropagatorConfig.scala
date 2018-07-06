package io.buoyant.linkerd.protocol.h2

import com.twitter.finagle.buoyant.h2.{Request, Response}
import com.twitter.finagle.{ServiceFactory, Stack, Stackable, param}
import com.twitter.finagle.tracing.TraceInitializerFilter
import io.buoyant.config.PolymorphicConfig
import io.buoyant.linkerd.TracePropagator
import io.buoyant.linkerd.TracePropagator.{ClientFilter, ServerFilter}

abstract class H2TracePropagatorConfig extends PolymorphicConfig {
  def mk(params: Stack.Params): TracePropagator[Request]
}

object H2TracePropagatorConfig {

  case class Param(tracePropagator: TracePropagator[Request])
  implicit val _Param: Stack.Param[Param] = new Stack.Param[Param] {
    override def default: Param = Param(new LinkerdTracePropagator)
  }

  val serverModule: Stackable[ServiceFactory[Request, Response]] = {
    new Stack.Module2[param.Tracer, Param, ServiceFactory[Request, Response]] {
      val role = TraceInitializerFilter.role
      val description = "Reads trace information from incoming request"

      def make(
        _tracer: param.Tracer,
        _tracePropagator: Param,
        next: ServiceFactory[Request, Response]
      ) = {
        val param.Tracer(tracer) = _tracer
        val Param(tracePropagator) = _tracePropagator
        new ServerFilter(tracePropagator, tracer) andThen next
      }
    }
  }

  val clientModule: Stackable[ServiceFactory[Request, Response]] = {
    new Stack.Module2[param.Tracer, Param, ServiceFactory[Request, Response]] {
      val role = TraceInitializerFilter.role
      val description = "Attaches trace information to the outgoing request"

      def make(
        _tracer: param.Tracer,
        _tracePropagator: Param,
        next: ServiceFactory[Request, Response]
      ) = {
        val param.Tracer(tracer) = _tracer
        val Param(tracePropagator) = _tracePropagator
        new ClientFilter(tracePropagator, tracer) andThen next
      }
    }
  }

}
