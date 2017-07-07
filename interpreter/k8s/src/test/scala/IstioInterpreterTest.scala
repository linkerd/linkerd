package io.buoyant.interpreter.k8s

import com.twitter.finagle.{Dtab, Path, Service}
import com.twitter.finagle.http.{Request, Response}
import com.twitter.util.Future
import io.buoyant.k8s.istio._
import io.buoyant.namer.Paths
import com.twitter.conversions.time._
import io.buoyant.k8s.SingleNsNamer
import io.buoyant.test.{Awaits, FunSuite}

class IstioInterpreterTest extends FunSuite with Awaits {

  val routeRulesList = s"""[
  {
   "type": "route-rule",
   "name": "httpbin-3s-rule",
   "spec": {
    "destination": "httpbin.default.svc.cluster.local",
    "httpReqTimeout": {
     "simpleTimeout": {
      "timeout": "3.000s"
     }
    }
   }
  }
 ]"""

  val apiserverService = Service.mk[Request, Response] {
    case req =>
      val rsp = Response()
      rsp.contentString = routeRulesList
      Future.value(rsp)
  }

  val discoveryService = Service.mk[Request, Response] {
    case req =>
      val rsp = Response()
      rsp.contentString = "[]"
      Future.value(rsp)
  }

  val client = new ApiserverClient(apiserverService, 5.seconds)
  val routeCache = new RouteCache(client)
  val discoveryClient = new DiscoveryClient(discoveryService, 5.seconds)

  test("successfully parses route-rules with no route field") {
    val istioNamer = new IstioNamer(discoveryClient, Paths.ConfiguredNamerPrefix ++ Path.read("/io.l5d.k8s.istio"))
    val k8sNamer = new SingleNsNamer(Path.read("/io.l5d.k8s.istio"), None, "default", null)
    val interpreter = IstioInterpreter(routeCache, istioNamer, k8sNamer)
    await(interpreter.bind(Dtab.empty, Path.read("/svc/route/httpbin-3s-rule")).values.toFuture.flatMap(Future.const))
  }

}
