package io.buoyant.test

import org.scalatest.Assertions

trait Exceptions extends Assertions {

  // assertThrows is like intercept, but discards the intercepted exception.
  def assertThrows[T <: AnyRef: Manifest](f: => Any): Unit = {
    val _ = intercept[T](f)
  }
}
