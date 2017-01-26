package io.buoyant.linkerd

import com.twitter.finagle.loadbalancer.{DefaultBalancerFactory, LoadBalancerFactory}
import org.scalatest.FunSuite

class LoadBalancerTest extends FunSuite {

  val balancers = Seq("p2c", "ewma", "aperture", "heap", "roundRobin")

  for (balancer <- balancers) {
    test(balancer) {
      val config = s"""
      |routers:
      |- protocol: plain
      |  client:
      |    loadBalancer:
      |      kind: $balancer
      |  servers:
      |  - {}
      |""".stripMargin

      val linker = Linker.load(config, Linker.Initializers(protocol = Seq(TestProtocol.Plain)))
      val factory = linker.routers.head.params[LoadBalancerFactory.Param]
      assert(factory.loadBalancerFactory != DefaultBalancerFactory)

      val enableProbation = linker.routers.head.params[LoadBalancerFactory.EnableProbation]
      assert(enableProbation == LoadBalancerFactory.EnableProbation(false))
    }

    test(balancer + " + enableProbation") {
      val config = s"""
      |routers:
      |- protocol: plain
      |  client:
      |    loadBalancer:
      |      kind: $balancer
      |      enableProbation: true
      |  servers:
      |  - {}
      |""".stripMargin

      val linker = Linker.load(config, Linker.Initializers(protocol = Seq(TestProtocol.Plain)))
      val factory = linker.routers.head.params[LoadBalancerFactory.Param]
      assert(factory.loadBalancerFactory != DefaultBalancerFactory)

      val enableProbation = linker.routers.head.params[LoadBalancerFactory.EnableProbation]
      assert(enableProbation == LoadBalancerFactory.EnableProbation(true))
    }
  }
}
