package io.buoyant.transformer

import com.twitter.finagle.{Address, Path}
import java.net.InetAddress
import io.buoyant.namer.FilteringNameTreeTransformer

/**
 * The subnet local transformer filters the list of addresses down to
 * only addresses that are on the same subnet as a given local IP.
 */
class SubnetLocalTransformer(val prefix: Path, localIP: Seq[InetAddress], netmask: Netmask)
  extends FilteringNameTreeTransformer {

  /** Use all addresses in the same subnet as localIP. */
  override protected val predicate: Address => Boolean = {
    case Address.Inet(addr, _) => localIP.exists(netmask.local(addr.getAddress, _))
    case address => true // non-inet addresses assumed to be local (pipes, etc)
  }
}
