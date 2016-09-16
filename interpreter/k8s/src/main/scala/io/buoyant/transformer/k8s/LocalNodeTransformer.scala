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
  assert(localAddress.size == 4, "LocalNodeTransformer only supports IPv4")

  override protected val isLocal: Address => Boolean = {
    case Address.Inet(addr, meta) =>
      val bytes = addr.getAddress.getAddress
      assert(bytes.size == 4, "LocalNodeTransformer only supports IPv4")
      // examine only the first 3 bytes (24 bits)
      bytes(0) == localAddress(0) &&
        bytes(1) == localAddress(1) &&
        bytes(2) == localAddress(2)
    case address => true
  }
}
