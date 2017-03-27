package io.buoyant.linkerd

import com.twitter.conversions.time._
import com.twitter.finagle.buoyant.TlsClientPrep.Trust
import com.twitter.finagle.loadbalancer.{Balancers, DefaultBalancerFactory, LoadBalancerFactory}
import com.twitter.finagle.Path
import io.buoyant.config.Parser
import io.buoyant.test.FunSuite

class ClientTest extends FunSuite {

  def parse(yaml: String): Client =
    Parser.objectMapper(yaml, Nil).readValue[Client](yaml)

  test("default applies to all clients") {
    val client = parse("""|loadBalancer:
                          |  kind: ewma""".stripMargin)

    val fooParams = client.clientParams.paramsFor(Path.read("/foo"))
    val LoadBalancerFactory.Param(fooBalancer) = fooParams[LoadBalancerFactory.Param]
    assert(fooBalancer.toString == "P2cPeakEwmaLoadBalancerFactory")
    val barParams = client.clientParams.paramsFor(Path.read("/bar"))
    val LoadBalancerFactory.Param(barBalancer) = barParams[LoadBalancerFactory.Param]
    assert(barBalancer.toString == "P2cPeakEwmaLoadBalancerFactory")
  }

  test("per client config") {
    val client = parse("""|kind: io.l5d.static
                          |configs:
                          |- prefix: "/#/io.l5d.fs/foo"
                          |  loadBalancer:
                          |    kind: ewma
                          |- prefix: "/#/io.l5d.fs/bar"
                          |  loadBalancer:
                          |    kind: aperture""".stripMargin)

    val fooParams = client.clientParams.paramsFor(Path.read("/#/io.l5d.fs/foo"))
    val LoadBalancerFactory.Param(fooBalancer) = fooParams[LoadBalancerFactory.Param]
    assert(fooBalancer.toString == "P2cPeakEwmaLoadBalancerFactory")

    val barParams = client.clientParams.paramsFor(Path.read("/#/io.l5d.fs/bar"))
    val LoadBalancerFactory.Param(barBalancer) = barParams[LoadBalancerFactory.Param]
    assert(barBalancer.toString == "ApertureLoadBalancerFactory")

    // bas, not configured, gets default values
    val basParams = client.clientParams.paramsFor(Path.read("/#/io.l5d.fs/bas"))
    val LoadBalancerFactory.Param(basBalancer) = basParams[LoadBalancerFactory.Param]
    assert(basBalancer == DefaultBalancerFactory)
  }

  test("later client configs override earlier ones") {
    val client = parse("""|kind: io.l5d.static
                          |configs:
                          |- prefix: "/"
                          |  loadBalancer:
                          |    kind: ewma
                          |- prefix: "/#/io.l5d.fs/bar"
                          |  loadBalancer:
                          |    kind: aperture""".stripMargin)

    val fooParams = client.clientParams.paramsFor(Path.read("/#/io.l5d.fs/foo"))
    val LoadBalancerFactory.Param(fooBalancer) = fooParams[LoadBalancerFactory.Param]
    assert(fooBalancer.toString == "P2cPeakEwmaLoadBalancerFactory")

    val barParams = client.clientParams.paramsFor(Path.read("/#/io.l5d.fs/bar"))
    val LoadBalancerFactory.Param(barBalancer) = barParams[LoadBalancerFactory.Param]
    assert(barBalancer.toString == "ApertureLoadBalancerFactory")
  }

  test("variable capture from prefix") {
    val client = parse("""|kind: io.l5d.static
                          |configs:
                          |- prefix: "/#/io.l5d.fs/{service}"
                          |  tls:
                          |    commonName: "{service}.com"
                          |- prefix: "/#/io.l5d.fs/bar"
                          |  tls:
                          |    commonName: barbarbar""".stripMargin)

    val fooParams = client.clientParams.paramsFor(Path.read("/#/io.l5d.fs/foo"))
    val Trust(Trust.Verified(fooCn, _)) = fooParams[Trust]
    assert(fooCn == "foo.com")

    val barParams = client.clientParams.paramsFor(Path.read("/#/io.l5d.fs/bar"))
    val Trust(Trust.Verified(barCn, _)) = barParams[Trust]
    assert(barCn == "barbarbar")

    val basParams = client.clientParams.paramsFor(Path.read("/#/io.l5d.wrong/bas"))
    assert(basParams[Trust].config == Trust.NotConfigured)
  }
}