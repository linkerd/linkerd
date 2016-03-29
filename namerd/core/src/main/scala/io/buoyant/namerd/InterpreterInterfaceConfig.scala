package io.buoyant.namerd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.{Dtab, Namer, Path}
import io.buoyant.config.ConfigInitializer

trait InterpreterInterfaceConfig extends InterfaceConfig {
  @JsonIgnore
  final def mk(store: DtabStore, namers: Seq[(Path, Namer)]): Servable =
    mk(ns => ConfiguredDtabNamer(store.observe(ns).map(extractDtab), namers))

  @JsonIgnore
  protected def mk(namers: Ns => NameInterpreter): Servable

  @JsonIgnore
  private[this] def extractDtab(versioned: Option[VersionedDtab]): Dtab =
    versioned.map(_.dtab).getOrElse(Dtab.empty)
}

trait InterpreterInterfaceInitializer extends ConfigInitializer
