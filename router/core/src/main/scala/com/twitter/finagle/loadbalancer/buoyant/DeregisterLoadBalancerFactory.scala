package com.twitter.finagle.loadbalancer
package buoyant

import com.twitter.finagle.{NoBrokersAvailableException, ServiceFactory, ServiceFactoryProxy, Stack}
import com.twitter.util.Activity

/**
 * Wraps a load balancer factory implementation and unregisters it from the
 * Finagle global balancer registry effectively disabling the use of the global registry.
 * This class was added to workaround a Finagle bug where the Balancer Registry leaked load balancer
 * instances. TODO: once https://github.com/twitter/finagle/issues/735 is fixed, we can get rid of
 * this workaround.
 *
 * @param lbf the actual Load Balancer instance that is wrapped by DeregisterLoadBalancerFactory
 */
case class DeregisterLoadBalancerFactory(lbf: LoadBalancerFactory) extends LoadBalancerFactory {
  private val globalBalancerRegistry = BalancerRegistry.get

  override def newBalancer[Req, Rep](
    endpoints: Activity[IndexedSeq[EndpointFactory[Req, Rep]]],
    emptyException: NoBrokersAvailableException,
    params: Stack.Params
  ): ServiceFactory[Req, Rep] = {
    val underlying = lbf.newBalancer(endpoints, emptyException, params)

    // A balancer is wrapped in a ServiceFactoryProxy so we need to unwrap it so that we
    // can actually remove it from the registry.
    underlying match {
      case svcFacProxy: ServiceFactoryProxy[Req, Rep] =>
        svcFacProxy.self match {
          case bal: Balancer[Req, Rep] => globalBalancerRegistry.unregister(bal)
          case _ => () // There are other types of balancers that aren't registerd i.e. heap. Do nothing
        }
      case _ => ()
    }
    underlying
  }
}
