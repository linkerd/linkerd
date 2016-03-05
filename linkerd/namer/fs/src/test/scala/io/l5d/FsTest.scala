package io.l5d

import com.twitter.finagle.Stack
import com.twitter.finagle.util.LoadService
import io.buoyant.linkerd.{NamerConfig, NamerInitializer}
import io.buoyant.linkerd.config.Parser
import io.buoyant.linkerd.config.types.Directory
import java.io.File
import java.nio.file.Paths
import org.scalatest.FunSuite
import scala.sys.process._

class FsTest extends FunSuite {

  test("sanity") {
    // ensure it doesn't totally blowup
    fs(Directory(Paths.get("."))).newNamer(Stack.Params.empty)
  }

  test("service registration") {
    assert(LoadService[NamerInitializer]().exists(_.isInstanceOf[FsInitializer]))
  }

  test("parse config") {
    val dir = new File("mktemp -d -t disco.XXX".!!.stripLineEnd)
    try {

      val yaml = s"""
        |kind: io.l5d.fs
        |rootDir: ${dir.getAbsolutePath}
      """.stripMargin

      val mapper = Parser.objectMapper(yaml, Seq(FsInitializer))
      val fs = mapper.readValue[NamerConfig](yaml).asInstanceOf[fs]
      assert(fs.rootDir.path == dir.toPath)
    } finally (Seq("rm", "-rf", dir.getPath).!)
  }
}
