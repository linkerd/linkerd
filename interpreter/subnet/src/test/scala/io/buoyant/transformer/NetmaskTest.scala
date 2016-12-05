package io.buoyant.transformer

import com.twitter.finagle.Address
import io.buoyant.namer.Netmask
import java.net.InetAddress
import org.scalatest.FunSuite

class NetmaskTest extends FunSuite {

  def testLocal(mask: Netmask, range1: Range, range2: Range, fmt: String): Unit =
    for {
      i <- range1.map(i => InetAddress.getByName(fmt.format(i)))
      j <- range2.map(j => InetAddress.getByName(fmt.format(j)))
    } assert(mask.local(i, j), s"${mask.address}: $i is not local to $j")

  def testNotLocal(mask: Netmask, range1: Range, range2: Range, fmt: String): Unit =
    for {
      i <- range1.map(i => InetAddress.getByName(fmt.format(i)))
      j <- range2.map(j => InetAddress.getByName(fmt.format(j)))
    } {
      if (i == j) assert(mask.local(i, j), s"${mask.address}: $i is not equal to $j")
      else assert(!mask.local(i, j), s"${mask.address}: $i is local to $j")
    }

  def testLocal(mask: Netmask, range: Range, fmt: String): Unit =
    testLocal(mask, range, range, fmt)

  def testNotLocal(mask: Netmask, range: Range, fmt: String): Unit =
    testNotLocal(mask, range, range, fmt)

  test("IPv4: computes a /16") {
    val netmask = Netmask("255.255.0.0")
    testLocal(netmask, 0 to 255, "10.0.0.%d")
    testLocal(netmask, 0 to 255, "10.0.%d.1")
    testNotLocal(netmask, 0 to 255, "10.%d.0.1")
    testNotLocal(netmask, 0 to 255, "%d.0.0.1")
  }

  test("IPv4: computes a /24") {
    val netmask = Netmask("255.255.255.0")
    testLocal(netmask, 0 to 255, "10.0.0.%d")
    testNotLocal(netmask, 0 to 255, "10.0.%d.1")
    testNotLocal(netmask, 0 to 255, "10.%d.0.1")
    testNotLocal(netmask, 0 to 255, "%d.0.0.1")
  }

  test("IPv4: computes a /25") {
    val netmask = Netmask("255.255.255.128")
    testLocal(netmask, 0 to 127, "10.0.0.%d")
    testLocal(netmask, 128 to 255, "10.0.0.%d")
    testNotLocal(netmask, 0 to 127, 128 to 255, "10.0.0.%d")
    testNotLocal(netmask, 128 to 255, 0 to 127, "10.0.0.%d")
    testNotLocal(netmask, 0 to 255, "10.0.%d.1")
    testNotLocal(netmask, 0 to 255, "10.%d.0.1")
    testNotLocal(netmask, 0 to 255, "%d.0.0.1")
  }

  test("IPv4: computes a /32") {
    val netmask = Netmask("255.255.255.255")
    testNotLocal(netmask, 0 to 255, "10.0.0.%d")
    testNotLocal(netmask, 0 to 255, "10.0.%d.1")
    testNotLocal(netmask, 0 to 255, "10.%d.0.1")
    testNotLocal(netmask, 0 to 255, "%d.0.0.1")
  }
}
