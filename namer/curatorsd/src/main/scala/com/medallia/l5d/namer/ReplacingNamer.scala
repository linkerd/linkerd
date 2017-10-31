package com.medallia.l5d.namer

import com.twitter.finagle.{Name, NameTree, Namer, Path}
import com.twitter.util.Activity

import scala.util.matching.Regex

class ReplacingNamer(matchingRegex: Regex, replacement: String) extends Namer {

  override def lookup(path: Path): Activity[NameTree[Name]] = {
    path.showElems.last match {
      case matchingRegex(_*) =>
        val elems: Seq[String] = path.showElems.init :+ replacement
        Activity.value(NameTree.Leaf(Name(Path.Utf8(elems: _*))))
      case _ => Activity.value(NameTree.Leaf(Name(path)))
    }
  }
}
