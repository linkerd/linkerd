package io.buoyant.namerd

import com.twitter.finagle.ListeningServer

case class Namerd(interfaces: Seq[Servable], dtabStore: DtabStore)

trait Servable {
  def kind: String
  def serve(): ListeningServer
}
