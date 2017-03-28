package io.buoyant.namer

import com.twitter.finagle.{Name, NameTree, Namer, Path}
import com.twitter.util.{Await, Future}
import org.scalatest.FunSuite

trait NamerTestUtil { self: FunSuite =>

  protected def lookup(namer: Namer, p: Path): NameTree[Name] =
    Await.result(namer.lookup(p).toFuture)

  protected def lookupBound(namer: Namer, p: Path): Seq[Name.Bound] =
    lookup(namer, p).eval.toSeq.flatten.collect {
      case bound: Name.Bound => bound
    }

  protected def assertBoundIdAutobinds(namer: Namer, path: Path, prefix: Path): Unit = {
    assert(path.startsWith(prefix))
    for (bound <- lookupBound(namer, path.drop(prefix.size))) {
      val idPath = bound.id.asInstanceOf[Path]
      assert(path.startsWith(prefix))
      val NameTree.Leaf(rebound: Name.Bound) = lookup(namer, idPath.drop(prefix.size))
      assert(rebound.id == bound.id)
    }
  }
}
