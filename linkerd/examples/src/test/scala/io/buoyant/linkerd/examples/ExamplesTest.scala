package io.buoyant.linkerd
package examples

import io.buoyant.config.Parser
import java.io.{FilenameFilter, File}
import org.scalatest.FunSuite
import scala.io.Source

class ExamplesTest extends FunSuite {

  val examplesDir = new File("linkerd/examples")
  val files = examplesDir.listFiles(new FilenameFilter {
    override def accept(dir: File, name: String): Boolean = name.endsWith(".yaml")
  })

  val mapper = Parser.jsonObjectMapper(Linker.LoadedInitializers.iter)

  for (file <- files) {
    test(file.getName) {
      val source = Source.fromFile(file)
      try {
        val lines = source.getLines().toSeq
        val firstLine = lines.headOption
        if (!firstLine.contains("#notest")) {
          val yaml = lines.mkString("\n")
          val parsed = Linker.parse(yaml)
          val loaded = parsed.mk()
          assert(mapper.writeValueAsString(parsed).nonEmpty)
        }
      } finally source.close()
    }
  }
}
