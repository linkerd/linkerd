package io.buoyant.transformer.k8s

import com.twitter.finagle.Address
import io.buoyant.transformer.perHost.LocalhostTransformer
import java.net.InetAddress

/**
 * The localnode transformer filters the list of addresses down to only
 * addresses that are on the same /24 subnet as localhost.  Since each k8s node
 * is its own /24 subnet, the result is that only addresses on the local node
 * are used.
 */
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
