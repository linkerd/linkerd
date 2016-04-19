package io.buoyant.namerd.storage.experimental.kubernetes

import io.buoyant.k8s.SerializationModule
import io.buoyant.namerd.DtabCodec

class DtabSerializationModule extends SerializationModule {
  def module = DtabCodec.module
}
