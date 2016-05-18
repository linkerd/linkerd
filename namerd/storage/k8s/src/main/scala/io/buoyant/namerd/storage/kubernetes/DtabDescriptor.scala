package io.buoyant.namerd.storage.kubernetes

import com.twitter.finagle.{Dtab => FDtab}
import io.buoyant.k8s._

object DtabDescriptor extends ObjectDescriptor[Dtab, DtabWatch] {
  def listName = "dtabs"
  def toWatch(d: Dtab) = DtabModified(d)
}

