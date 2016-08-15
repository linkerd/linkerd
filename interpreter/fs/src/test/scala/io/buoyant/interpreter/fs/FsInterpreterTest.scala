package io.buoyant.interpreter.fs

import com.twitter.finagle.util.LoadService
import io.buoyant.config.Parser
import io.buoyant.namer.{InterpreterConfig, InterpreterInitializer}
import java.io.File
import org.scalatest.FunSuite

class FsInterpreterTest extends FunSuite {

  test("interpreter registration") {
    assert(LoadService[InterpreterInitializer]().exists(_.isInstanceOf[FsInterpreterInitializer]))
  }

  test("parse config") {
    val dtabFile = File.createTempFile("example", ".dtab")
    try {
      val yaml =
        s"""|kind: io.l5d.fs
            |dtabFile: ${dtabFile.getPath}
            |""".stripMargin

      val mapper = Parser.objectMapper(yaml, Iterable(Seq(FsInterpreterInitializer)))
      val fs = mapper.readValue[InterpreterConfig](yaml).asInstanceOf[FsInterpreterConfig]
      assert(fs.dtabFile.path.toString == dtabFile.getPath)
    } finally {
      val _ = dtabFile.delete()
    }
  }
}

