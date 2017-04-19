package io.buoyant.linkerd

import com.twitter.conversions.time._
import com.twitter.finagle.buoyant.TlsClientPrep.Trust
import com.twitter.finagle.loadbalancer.{Balancers, DefaultBalancerFactory, LoadBalancerFactory}
import com.twitter.finagle.service.exp.FailureAccrualPolicy
import com.twitter.finagle.service.FailureAccrualFactory
import com.twitter.finagle.Path
import com.twitter.util.Duration
import io.buoyant.config.Parser
import io.buoyant.test.FunSuite
import scala.language.reflectiveCalls

class ClientTest extends FunSuite {

  def parse(yaml: String): Client =
    Parser.objectMapper(yaml, Seq(Seq(new FooFailureAccrual))).readValue[Client](yaml)

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

  test("failure accrual") {
    val client = parse("""|kind: io.l5d.static
                          |configs:
                          |- prefix: "/"
                          |  failureAccrual:
                          |    kind: io.l5d.foo
                          |- prefix: "/#/io.l5d.fs/foo"
                          |  # this should inherit the failure accrual policy
                          |  # from above
                          |""".stripMargin)

    val fooParams = client.clientParams.paramsFor(Path.read("/#/io.l5d.fs/foo"))
    val policy = fooParams[FailureAccrualFactory.Param]
      .asInstanceOf[{ def failureAccrualPolicy: () => FailureAccrualPolicy }]
      .failureAccrualPolicy()
    assert(policy.toString == "FooFailureAccrual")
  }
}

class FooFailureAccrual extends FailureAccrualInitializer {
  val configClass = classOf[FooConfig]
  override def configId = "io.l5d.foo"
}

class FooConfig extends FailureAccrualConfig {
  override def policy =
    () => new FailureAccrualPolicy {
      def recordSuccess(): Unit = ???
      def markDeadOnFailure(): Option[Duration] = ???
      def revived(): Unit = ???
      override def toString = "FooFailureAccrual"
    }
}