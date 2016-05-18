package io.buoyant.namer.fs

import com.twitter.finagle.util.LoadService
import com.twitter.finagle.{Path, Stack}
import io.buoyant.config.Parser
import io.buoyant.config.types.Directory
import io.buoyant.namer.{NamerConfig, NamerInitializer, NamerTestUtil}
import java.io.{File, PrintWriter}
import java.nio.file.Paths
import org.scalatest.FunSuite
import scala.sys.process._

class FsTest extends FunSuite with NamerTestUtil {

  test("sanity") {
    // ensure it doesn't totally blowup
    val _ = FsConfig(Directory(Paths.get("."))).newNamer(Stack.Params.empty)
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

      val mapper = Parser.objectMapper(yaml, Iterable(Seq(FsInitializer)))
      val fs = mapper.readValue[NamerConfig](yaml).asInstanceOf[FsConfig]
      assert(fs.rootDir.path == dir.toPath)
    } finally {
      val _ = Seq("rm", "-rf", dir.getPath).!
    }
  }

  test("id is bound name") {
    val path = Path.read("/#/io.l5d.fs/default")

    val dir = new File("mktemp -d -t disco.XXX".!!.stripLineEnd)
    try {

      val default = new File(dir, "default")
      val writer = new PrintWriter(default)
      writer.println("127.0.0.1 8080")
      writer.close()

      val yaml = s"""
                    |kind: io.l5d.fs
                    |rootDir: ${dir.getAbsolutePath}
      """.stripMargin

      val mapper = Parser.objectMapper(yaml, Iterable(Seq(FsInitializer)))
      val fs = mapper.readValue[NamerConfig](yaml)
      val namer = fs.newNamer(Stack.Params.empty)

      assertBoundIdAutobinds(namer, path, fs.prefix)

    } finally {
      val _ = Seq("rm", "-rf", dir.getPath).!
    }

  }
}
