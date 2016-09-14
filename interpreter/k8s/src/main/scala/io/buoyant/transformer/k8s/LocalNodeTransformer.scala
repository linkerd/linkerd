package io.buoyant.transformer.k8s

import com.twitter.finagle.Address
import io.buoyant.transformer.perHost.LocalhostTransformer
import java.net.InetAddress

class LocalNodeTransformer(local: InetAddress) extends LocalhostTransformer {

  private[this] val localAddress = local.getAddress

  override protected val isLocal: Address => Boolean = {
    case Address.Inet(addr, meta) =>
      val bytes = addr.getAddress.getAddress
      // examine only the first 3 bytes (24 bits)
      localAddress.zip(bytes).take(3).forall(tupleEqual)
    case address => true
  }
}
