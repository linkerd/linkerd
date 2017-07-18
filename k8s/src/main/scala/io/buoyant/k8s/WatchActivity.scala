package io.buoyant.k8s

import java.util.concurrent.atomic.AtomicReference
import com.twitter.util.{Activity, Closable, Var}

/**
  * A [[Watchable]] Kubernetes object that can be converted to an [[com.twitter.util.Activity]]
  *
  * @tparam O
  * @tparam W
  * @tparam G
  */
trait WatchActivity[O, W, G]
  extends Watchable[O, W, G] {


}
