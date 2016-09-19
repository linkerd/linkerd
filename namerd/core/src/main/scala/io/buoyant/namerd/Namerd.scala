package io.buoyant.namerd

import com.twitter.finagle.{Dtab, Path, Namer, ListeningServer}
import com.twitter.util.Activity
import io.buoyant.admin.Admin
import io.buoyant.telemetry.Telemeter

private[namerd] case class Namerd(
  interfaces: Seq[Servable],
  dtabStore: DtabStore,
  namers: Map[Path, Namer],
  admin: Admin,
  telemeters: Seq[Telemeter]
) {

  def extractDtab(ns: Ns): Activity[Dtab] = dtabStore.observe(ns).map {
    case Some(dtab) => dtab.dtab
    case None => Dtab.empty
  }
}

trait Servable {
  def kind: String
  def serve(): ListeningServer
}
