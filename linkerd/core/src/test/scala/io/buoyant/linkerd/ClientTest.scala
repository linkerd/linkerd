package io.buoyant.linkerd

import com.twitter.finagle.Path
import com.twitter.finagle.liveness.{FailureAccrualFactory, FailureAccrualPolicy}
import com.twitter.finagle.loadbalancer.LoadBalancerFactory._
import com.twitter.finagle.loadbalancer.buoyant.DeregisterLoadBalancerFactory
import com.twitter.finagle.loadbalancer.{FlagBalancerFactory, LoadBalancerFactory}
import com.twitter.finagle.ssl.client.SslClientConfiguration
import com.twitter.finagle.transport.Transport
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
    val Param(fooBalancer) = fooParams[LoadBalancerFactory.Param]
    val fooBal = fooBalancer match {
      case DeregisterLoadBalancerFactory(lbf) => lbf
      case _ => fail("Unexpected load balancer configured")
    }
    assert(fooBal.toString == "P2CPeakEwma")
    val barParams = client.clientParams.paramsFor(Path.read("/bar"))
    val Param(barBalancer) = barParams[LoadBalancerFactory.Param]
    val barBal = barBalancer match {
      case DeregisterLoadBalancerFactory(lbf) => lbf
      case _ => fail("Unexpected load balancer configured")
    }
    assert(barBal.toString == "P2CPeakEwma")
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
    val Param(fooBalancer) = fooParams[LoadBalancerFactory.Param]
    val fooBal = fooBalancer match {
      case DeregisterLoadBalancerFactory(lbf) => lbf
      case _ => fail("Unexpected load balancer configured")
    }
    assert(fooBal.toString == "P2CPeakEwma")

    val barParams = client.clientParams.paramsFor(Path.read("/#/io.l5d.fs/bar"))
    val Param(barBalancer) = barParams[LoadBalancerFactory.Param]
    val barBal = barBalancer match {
      case DeregisterLoadBalancerFactory(lbf) => lbf
      case _ => fail("Unexpected load balancer configured")
    }
    assert(barBal.toString == "ApertureLeastLoaded")

    // bas, not configured, gets default values
    val basParams = client.clientParams.paramsFor(Path.read("/#/io.l5d.fs/bas"))
    val Param(basBalancer) = basParams[LoadBalancerFactory.Param]
    val basBal = basBalancer match {
      case DeregisterLoadBalancerFactory(lbf) => lbf
      case flb: LoadBalancerFactory => flb
      case _ => fail("Unexpected load balancer configured")
    }
    assert(basBal == FlagBalancerFactory)
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
    val Param(fooBalancer) = fooParams[LoadBalancerFactory.Param]
    val fooBal = fooBalancer match {
      case DeregisterLoadBalancerFactory(lbf) => lbf
      case _ => fail("Unexpected load balancer configured")
    }
    assert(fooBal.toString == "P2CPeakEwma")

    val barParams = client.clientParams.paramsFor(Path.read("/#/io.l5d.fs/bar"))
    val Param(barBalancer) = barParams[LoadBalancerFactory.Param]
    val barBal = barBalancer match {
      case DeregisterLoadBalancerFactory(lbf) => lbf
      case _ => fail("Unexpected load balancer configured")
    }
    assert(barBal.toString == "ApertureLeastLoaded")
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
    val Transport.ClientSsl(Some(SslClientConfiguration(Some(fooCn), _, _, _, _, _))) =
      fooParams[Transport.ClientSsl]
    assert(fooCn == "foo.com")

    val barParams = client.clientParams.paramsFor(Path.read("/#/io.l5d.fs/bar"))
    val Transport.ClientSsl(Some(SslClientConfiguration(Some(barCn), _, _, _, _, _))) =
      barParams[Transport.ClientSsl]
    assert(barCn == "barbarbar")

    val basParams = client.clientParams.paramsFor(Path.read("/#/io.l5d.wrong/bas"))
    assert(basParams[Transport.ClientSsl].sslClientConfiguration.isEmpty)
  }

  test("variable capture from prefix with fragment") {
    val client = parse("""|kind: io.l5d.static
                          |configs:
                          |- prefix: "/#/io.l5d.serversets/s/*/staging/{service}:https"
                          |  tls:
                          |    commonName: "*.{service}.com"
                          |- prefix: "/#/io.l5d.serversets/s/{role}/prod/{service}:https"
                          |  tls:
                          |    commonName: "{role}.{service}.com"""".stripMargin)

    val fooParams = client.clientParams.paramsFor(Path.read("/#/io.l5d.serversets/s/nobody/staging/foo:https"))
    val Transport.ClientSsl(Some(SslClientConfiguration(Some(fooCn), _, _, _, _, _))) =
      fooParams[Transport.ClientSsl]
    assert(fooCn == "*.foo.com")

    val barParams = client.clientParams.paramsFor(Path.read("/#/io.l5d.serversets/s/www/prod/bar:https"))
    val Transport.ClientSsl(Some(SslClientConfiguration(Some(barCn), _, _, _, _, _))) =
      barParams[Transport.ClientSsl]
    assert(barCn == "www.bar.com")

    val basParams = client.clientParams.paramsFor(Path.read("/#/io.l5d.wrong/bas"))
    assert(basParams[Transport.ClientSsl].sslClientConfiguration.isEmpty)
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
      override def name: String = "FooConfig"
      override def show(): String = ""
    }
}
