package io.buoyant.test

import org.scalatest.Assertions

trait Exceptions extends Assertions {

  def assertThrows[T <: AnyRef: Manifest](f: => Any): Unit = {
    val _ = intercept[T](f)
  }
}
