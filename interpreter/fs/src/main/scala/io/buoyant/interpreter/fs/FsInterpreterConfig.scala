package io.buoyant.interpreter.fs

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.Dtab
import com.twitter.finagle.Stack.Params
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.io.Buf
import com.twitter.util.Activity
import io.buoyant.config.types.File
import io.buoyant.namer.{Param, ConfiguredDtabNamer, InterpreterConfig}
import io.buoyant.namer.fs.Watcher

case class FsInterpreterConfig(dtabFile: File) extends InterpreterConfig {

  @JsonIgnore
  private[this] val path = dtabFile.path

  @JsonIgnore
  private[this] def dtab: Activity[Dtab] =
    Watcher(path.getParent).children.flatMap { children =>
      children.get(path.getFileName.toString) match {
        case Some(file: Watcher.File.Reg) => file.data
        case _ => Activity.pending
      }
    }.map {
      case Buf.Utf8(dtab) =>
        Dtab.read(dtab)
    }

  @JsonIgnore
  override def newInterpreter(params: Params): NameInterpreter = {
    val Param.Namers(namers) = params[Param.Namers]
    ConfiguredDtabNamer(dtab, namers)
  }
}
