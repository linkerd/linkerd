package io.buoyant.namer

import com.twitter.finagle.{Name, NameTree, Path, Namer}
import com.twitter.util.{Activity, Future}

trait EnumeratingNamer extends Namer {
  def getAllNames: Future[Set[Path]]
}

object EnumeratingNamer {
  def apply(namer: Namer): EnumeratingNamer = new EnumeratingNamer {
    def getAllNames: Future[Set[Path]] = Future.value(Set.empty)
    def lookup(path: Path): Activity[NameTree[Name]] = namer.lookup(path)
  }
}
