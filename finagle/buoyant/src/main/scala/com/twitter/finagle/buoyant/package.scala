package com.twitter.finagle

import com.twitter.finagle.Stack.Parameterized
import com.twitter.finagle.service.ResponseClassificationSyntheticException

package object buoyant {

  implicit class ParamsMaybeWith(val params: Stack.Params) extends AnyVal {
    def maybeWith[T: Stack.Param](p: Option[T]): Stack.Params = {
      p match {
        case Some(t) => params + t
        case None => params
      }
    }

    def maybeWith(ps: Option[Stack.Params]): Stack.Params = {
      ps match {
        case Some(ps) => params ++ ps
        case None => params
      }
    }
  }

  implicit class ParameterizedMaybeWith[P <: Parameterized[P]](val self: P) extends AnyVal {
    def maybeWith(ps: Option[Stack.Params]): P = {
      ps match {
        case Some(params) => self.withParams(params)
        case None => self
      }
    }
  }

  implicit class MaybeTransform[A](val a: A) extends AnyVal {
    def maybeTransform(f: Option[A => A]): A = {
      f match {
        case Some(f) => f(a)
        case None => a
      }
    }
  }

  /**
   * Reexport ResponseClassificationSyntheticException
   * publicly so it can be used in H2 `StreamStatsFilter`.
   * @return a ResponseClassificationSyntheticException
   */
  def syntheticException: ResponseClassificationSyntheticException = new ResponseClassificationSyntheticException()
}
