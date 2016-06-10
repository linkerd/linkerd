package io.buoyant.namer

import com.twitter.finagle.{Name, NameTree, Path, Namer}
import com.twitter.util.{Activity, Future}

trait EnumeratingNamer extends Namer {
  def getAllNames: Activity[Set[Path]]
}
