package io.buoyant.linkerd

import com.twitter.finagle.Path
import com.twitter.finagle.liveness.{FailureAccrualFactory, FailureAccrualPolicy}
import com.twitter.finagle.loadbalancer.{DefaultBalancerFactory, LoadBalancerFactory}
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
    val LoadBalancerFactory.Param(fooBalancer) = fooParams[LoadBalancerFactory.Param]
    assert(fooBalancer.toString == "P2CPeakEwma")
    val barParams = client.clientParams.paramsFor(Path.read("/bar"))
    val LoadBalancerFactory.Param(barBalancer) = barParams[LoadBalancerFactory.Param]
    assert(barBalancer.toString == "P2CPeakEwma")
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
    assert(fooBalancer.toString == "P2CPeakEwma")

    val barParams = client.clientParams.paramsFor(Path.read("/#/io.l5d.fs/bar"))
    val LoadBalancerFactory.Param(barBalancer) = barParams[LoadBalancerFactory.Param]
    assert(barBalancer.toString == "ApertureLeastLoaded")

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
    assert(fooBalancer.toString == "P2CPeakEwma")

    val barParams = client.clientParams.paramsFor(Path.read("/#/io.l5d.fs/bar"))
    val LoadBalancerFactory.Param(barBalancer) = barParams[LoadBalancerFactory.Param]
    assert(barBalancer.toString == "ApertureLeastLoaded")
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
