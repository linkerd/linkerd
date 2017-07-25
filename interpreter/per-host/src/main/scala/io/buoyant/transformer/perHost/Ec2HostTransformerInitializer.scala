package io.buoyant.transformer
package perHost

import java.net.InetAddress

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Http, Path, Stack, http}
import com.twitter.util.Activity
import io.buoyant.namer.{NameTreeTransformer, TransformerConfig, TransformerInitializer}

class Ec2HostTransformerInitializer extends TransformerInitializer {
  val configClass = classOf[Ec2HostTransformerConfig]
  override val configId = "io.l5d.ec2Host"
}

class Ec2HostTransformerConfig() extends TransformerConfig {

  private val awsMetaAddress = "169.254.169.254"
  private val port = 80

  @JsonIgnore
  val defaultPrefix = Path.read("/io.l5d.ec2Host")

  @JsonIgnore
  override def mk(params: Stack.Params): NameTreeTransformer = {

    val service = Http.client
      .newService(s"/$$/inet/$awsMetaAddress/$port")

    val req = http.Request("latest/meta-data/local-ipv4")
    val activity = Activity.future(service.apply(req).map(r => Seq(InetAddress.getByName(r.getContentString()))))

    new FutureSubnetLocalTransformer(prefix, activity, Netmask("255.255.255.255"))
  }

}
