package io.buoyant.namerd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.{Dtab, Namer, Path}
import io.buoyant.config.ConfigInitializer

trait InterpreterInterfaceConfig extends InterfaceConfig {
  @JsonIgnore
  final def mk(store: DtabStore, namers: Map[Path, Namer], stats: StatsReceiver): Servable =
    mk(
      ns => ConfiguredDtabNamer(store.observe(ns).map(extractDtab), namers.toSeq),
      namers,
      store,
      stats
    )

  @JsonIgnore
  protected def mk(
    delegate: Ns => NameInterpreter,
    namers: Map[Path, Namer],
    store: DtabStore,
    stats: StatsReceiver
  ): Servable

  @JsonIgnore
  private[this] def extractDtab(versioned: Option[VersionedDtab]): Dtab =
    versioned.map(_.dtab).getOrElse(Dtab.empty)
}

abstract class InterpreterInterfaceInitializer extends ConfigInitializer
