package io.buoyant.transformer

import java.net.InetAddress

import com.twitter.finagle.{Address, Path}
import com.twitter.util.Activity
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

class FutureSubnetLocalTransformer(val prefix: Path, val futureIps: Activity[Seq[InetAddress]], netmask: Netmask)
  extends FilteringNameTreeTransformer {

  override protected val predicate: Address => Boolean = {

    case Address.Inet(addr, _) => {
      futureIps.run.map {
        case Activity.Ok(ips) => ips.exists(netmask.local(addr.getAddress, _))
        case _ => false
      }.sample()
    }
    case _ => true // non-inet addresses assumed to be local (pipes, etc)

  }

}
