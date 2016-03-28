package io.buoyant.namer

import com.twitter.finagle.{Name, NameTree, Namer, Path}
import com.twitter.util.{Await, Future}
import org.scalatest.FunSuite

trait NamerTestUtil { self: FunSuite =>

  protected def assertBoundIdAutobinds(namer: Namer, path: Path, prefix: Path): Unit = {
    def lookup(p: Path) = {
      assert(p.startsWith(prefix))
      val pathWithoutPrefix = p.drop(prefix.size)
      Await.result(namer.lookup(pathWithoutPrefix).values.toFuture.flatMap(Future.const))
    }

    val nameTree = lookup(path)
    val boundNames = nameTree.eval.toSet.flatten.collect {
      case bound: Name.Bound => bound
    }
    for (bound <- boundNames) {
      val idPath = bound.id.asInstanceOf[Path]
      val NameTree.Leaf(rebound: Name.Bound) = lookup(idPath)
      assert(rebound.id == bound.id)
    }
  }
}
