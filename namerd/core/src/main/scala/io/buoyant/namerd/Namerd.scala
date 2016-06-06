package io.buoyant.namerd

import com.twitter.finagle.{Dtab, Path, Namer, ListeningServer}
import com.twitter.util.Activity

case class Namerd(interfaces: Seq[Servable], dtabStore: DtabStore, namers: Map[Path, Namer]) {

  def extractDtab(ns: Ns): Activity[Dtab] = dtabStore.observe(ns).map {
    case Some(dtab) => dtab.dtab
    case None => Dtab.empty
  }
}

trait Servable {
  def kind: String
  def serve(): ListeningServer
}
