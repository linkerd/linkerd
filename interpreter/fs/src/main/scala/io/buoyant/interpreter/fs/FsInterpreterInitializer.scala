package io.buoyant.interpreter.fs

import io.buoyant.namer.InterpreterInitializer

class FsInterpreterInitializer extends InterpreterInitializer {
  override def configClass: Class[_] = classOf[FsInterpreterConfig]
  override def configId: String = "io.l5d.fs"
}

object FsInterpreterInitializer extends FsInterpreterInitializer
