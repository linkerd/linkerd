package io.buoyant.linkerd.examples

import io.buoyant.linkerd.Linker
import java.io.{FilenameFilter, File}
import org.scalatest.FunSuite
import scala.io.Source

class ExamplesTest extends FunSuite {

  val examplesDir = new File("linkerd/examples")
  val files = examplesDir.listFiles(new FilenameFilter {
    override def accept(dir: File, name: String): Boolean = name.endsWith(".yaml")
  })

  for (file <- files) {
    test(file.getName) {
      val source = Source.fromFile(file)
      try {
        val lines = source.getLines().toSeq
        val firstLine = lines.headOption
        if (!firstLine.contains("#notest")) {
          val config = lines.mkString("\n")
          val _ = Linker.load(config)
        }
      } finally {
        source.close()
      }
    }
  }
}
