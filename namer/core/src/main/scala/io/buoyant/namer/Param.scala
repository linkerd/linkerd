package io.buoyant.namer

import com.twitter.finagle.{Stack, Namer, Path}

object Param {
  case class Namers(namers: Seq[(Path, Namer)])
  implicit object Namers extends Stack.Param[Namers] {
    val default = Namers(Nil)
  }
}
