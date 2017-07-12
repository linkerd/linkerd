package com.twitter.finagle

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
}
