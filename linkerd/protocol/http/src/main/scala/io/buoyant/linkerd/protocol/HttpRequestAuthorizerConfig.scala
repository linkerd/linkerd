package io.buoyant.linkerd.protocol

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Filter, Service, ServiceFactory, Stack, Stackable}
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.stack.nilStack
import com.twitter.util.Future
import io.buoyant.config.PolymorphicConfig

abstract class HttpRequestAuthorizerConfig extends PolymorphicConfig { config =>

  @JsonIgnore
  def role: Stack.Role
  @JsonIgnore
  def description: String
  @JsonIgnore
  def parameters: Seq[Stack.Param[_]]

  @JsonIgnore
  def mk(params: Stack.Params): Filter[Request, Response, Request, Response]

  @JsonIgnore
  def module = new Stack.Module[ServiceFactory[Request, Response]] {
    override def role: Stack.Role = config.role
    override def description: String = config.description
    override def parameters: Seq[Stack.Param[_]] = config.parameters

    override def make(
      params: Stack.Params,
      next: Stack[ServiceFactory[Request, Response]]
    ): Stack[ServiceFactory[Request, Response]] = {
      val filter = mk(params)
      Stack.Leaf(role, filter.andThen(next.make(params)))
    }
  }
}

object HttpRequestAuthorizerConfig {
  object param {
    case class RequestAuthorizer(loggerStack: Stack[ServiceFactory[Request, Response]])
    implicit object RequestAuthorizer extends Stack.Param[RequestAuthorizer] {
      val default = RequestAuthorizer(nilStack)
    }
  }

  def module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module[ServiceFactory[Request, Response]] {
      override val role = Stack.Role("HttpRequestAuthorizer")
      override val description = "HTTP RequestAuthorizer"
      override val parameters = Seq(implicitly[Stack.Param[param.RequestAuthorizer]])
      def make(params: Stack.Params, next: Stack[ServiceFactory[Request, Response]]): Stack[ServiceFactory[Request, Response]] = {
        val param.RequestAuthorizer(requestAuthorizerStack) = params[param.RequestAuthorizer]
        requestAuthorizerStack ++ next
      }
    }
}
