package io.buoyant.namer

import com.twitter.finagle.Address
import java.net.InetAddress

/**
 * The subnet local transformer filters the list of addresses down to
 * only addresses that are on the same subnet as a given local IP.
 */
class SubnetLocalTransformer(localIP: InetAddress, netmask: Netmask)
  extends FilteringNameTreeTransformer {

  /** Use all addresses in the same subnet as localIP. */
  override protected val predicate: Address => Boolean = {
    case Address.Inet(addr, _) => netmask.local(addr.getAddress, localIP)
    case address => true // non-inet addresses assumed to be local (pipes, etc)
  }
}
