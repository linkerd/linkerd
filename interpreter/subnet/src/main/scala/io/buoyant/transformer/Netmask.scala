package io.buoyant.transformer

import com.twitter.finagle.Address
import java.net.InetAddress

object Netmask {
  def apply(mask: String): Netmask =
    Netmask(InetAddress.getByName(mask))
}

case class Netmask(address: InetAddress) {
  private[this] val maskBytes = address.getAddress

  def local(a1: Address, a2: Address): Boolean = (a1, a2) match {
    case (Address.Inet(isa1, _), Address.Inet(isa2, _)) =>
      local(isa1.getAddress, isa2.getAddress)
    case _ => false
  }

  /** Determine if two IPs are on the same subnet. */
  def local(ip1: InetAddress, ip2: InetAddress): Boolean = {
    val b1 = ip1.getAddress
    val b2 = ip2.getAddress
    if (!(b1.length == maskBytes.length && b2.length == maskBytes.length))
      return false // comparing different address families

    var matches = true
    var i = 0
    while (matches && i != maskBytes.length) {
      val net1 = maskBytes(i) & b1(i)
      val net2 = maskBytes(i) & b2(i)
      matches = (net1 == net2)
      i += 1
    }
    matches
  }
}
