package io.buoyant.namer.curator

import java.net.URL

import com.twitter.finagle.Address
import org.apache.curator.x.discovery.ServiceInstance

class CustomCuratorNamer(zookeeperConnectionString: String, baseZnodePath: String) extends CuratorNamer(zookeeperConnectionString, baseZnodePath) {

  override def isSSL(instance: ServiceInstance[Void]) = {
    instance.getAddress.startsWith("https")
  }

  override def getAddress(instance: ServiceInstance[Void]) = {
    val url = new URL(instance.getAddress)
    Address(url.getHost, url.getPort)
  }

}
