package io.l5d

import com.twitter.finagle.Stack
import com.twitter.finagle.util.LoadService
import io.buoyant.linkerd.NamerInitializer
import io.buoyant.linkerd.config.types.Directory
import java.nio.file.Paths
import org.scalatest.FunSuite

class FsTest extends FunSuite {

  test("sanity") {
    // ensure it doesn't totally blowup
    fs(Directory(Paths.get("."))).newNamer(Stack.Params.empty)
  }

  test("service registration") {
    assert(LoadService[NamerInitializer]().exists(_.isInstanceOf[FsInitializer]))
  }
}
