package io.buoyant.transformer
package perHost

import com.fasterxml.jackson.annotation.JsonIgnore
import io.buoyant.namer.{NameTreeTransformer, TransformerConfig, TransformerInitializer}
import java.net.InetAddress
import com.twitter.finagle.{Http, Stack, http, Path}
import com.twitter.util.Await

class Ec2HostTransformerInitializer extends TransformerInitializer {
  val configClass = classOf[Ec2HostTransformerConfig]
  override val configId = "io.l5d.ec2Host"
}

class Ec2HostTransformerConfig extends TransformerConfig {

  @JsonIgnore
  val defaultPrefix = Path.read("/io.l5d.ec2Host")

  @JsonIgnore
  override def mk(params: Stack.Params): NameTreeTransformer = {

    val servicePort = 80
    val serviceHost = "169.254.169.254"
    val service = Http.client
      .newService(s"/$$/inet/$serviceHost/$servicePort")

    val req = http.Request("latest/meta-data/local-ipv4")
    req.method = http.Method.Get
    val response = Await.result(service.apply(req))
    val ec2HostIp = response.contentString

    new SubnetLocalTransformer(prefix, Seq(InetAddress.getByName(ec2HostIp)), Netmask("255.255.255.255"))
  }

}
