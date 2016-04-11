package io.buoyant.namerd

import com.twitter.finagle.{Path, Namer, ListeningServer}

case class Namerd(interfaces: Seq[Servable], dtabStore: DtabStore, namers: Map[Path, Namer])

trait Servable {
  def kind: String
  def serve(): ListeningServer
}
