package io.buoyant.linkerd.protocol.http

import com.twitter.conversions.time._
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.http.{Request => FRequest, Response => FResponse}
import com.twitter.finagle.{Dtab, Path, Service}
import com.twitter.util.Future
import io.buoyant.k8s.{ClusterCache, IstioPilotClient, RouteManager}
import io.buoyant.router.RoutingFactory._
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class IstioIdentifierTest extends FunSuite with Awaits {

  val routeRulesList = s"""[
  {
   "type": "route-rule",
   "name": "bird-watcher-default",
   "spec": {
    "destination": "bird-watcher.default.svc.cluster.local",
    "precedence": 1,
    "route": []
   }
  },
  {
   "type": "route-rule",
   "name": "flush",
   "spec": {
    "destination": "poker.default.svc.cluster.local",
    "precedence": 4,
    "route": []
   }
  },
  {
   "type": "route-rule",
   "name": "straight-flush",
   "spec": {
    "destination": "poker.default.svc.cluster.local",
    "precedence": 8,
    "route": [
     {
      "tags": {
       "version": "straight"
      }
     }
    ]
   }
  }
 ]"""

  val routesList = s"""
  [{
   "route-config-name": "80",
   "service-cluster": "istio-proxy",
   "service-node": "172.17.0.8",
   "virtual_hosts": [
    {
     "name": "bird-watcher.default.svc.cluster.local|binoculars",
     "domains": [
      "bird-watcher:80",
      "bird-watcher",
      "bird-watcher.default:80",
      "bird-watcher.default",
      "bird-watcher.default.svc:80",
      "bird-watcher.default.svc",
      "bird-watcher.default.svc.cluster:80",
      "bird-watcher.default.svc.cluster",
      "bird-watcher.default.svc.cluster.local:80",
      "bird-watcher.default.svc.cluster.local",
      "10.0.0.106:80",
      "10.0.0.106"
     ],
     "routes": [
      {
       "prefix": "/",
       "cluster": "out.fd518f1d0ba070c47739cbf6b191f85eb1cdda3d"
      }
     ]
    }
   ]
  },
  {
   "route-config-name": "8080",
   "service-cluster": "istio-proxy",
   "service-node": "10.0.2.15",
   "virtual_hosts": [
    {
     "name": "linkerd-fan.default.svc.cluster.local|fan-power",
     "domains": [
      "linkerd-fan:8080",
      "linkerd-fan.default:8080",
      "linkerd-fan.default.svc:8080",
      "linkerd-fan.default.svc.cluster:8080",
      "linkerd-fan.default.svc.cluster.local:8080",
      "10.0.0.163:8080"
     ]
    }
   ]
  },
  {
   "route-config-name": "8080",
   "service-cluster": "istio-proxy",
   "service-node": "10.0.2.15",
   "virtual_hosts": [
    {
     "name": "poker.default.svc.cluster.local|call",
     "domains": [
      "poker:8080",
      "poker.default:8080",
      "poker.default.svc:8080",
      "poker.default.svc.cluster:8080",
      "poker.default.svc.cluster.local:8080",
      "10.0.0.169:8080"
     ],
     "routes": [
      {
       "prefix": "/",
       "cluster": "out.693aed113867a238731ac766defd51ac0757a6a8"
      }
     ]
    }
   ]
  }]
"""

  val pilotService = Service.mk[FRequest, FResponse] {
    case req =>
      val rsp = FResponse()
      rsp.contentString = routeRulesList
      Future.value(rsp)
  }

  val clusterService = Service.mk[FRequest, FResponse] {
    case req =>
      val rsp = FResponse()
      rsp.contentString = routesList
      Future.value(rsp)
  }

  val client = new IstioPilotClient(pilotService)
  val routeManager = new RouteManager(client, 5.seconds)
  val clusterCache = new ClusterCache(clusterService)
  val identifier = new IstioIdentifier(Path.Utf8("svc"), () => Dtab.base, routeManager, clusterCache)

  test("forwards requests if host doesn't match any vhosts") {
    val req = FRequest()
    req.host = "hello.world.com"
    await(identifier(req)) match {
      case IdentifiedRequest(Dst.Path(name, base, local), req1) =>
        assert(name == Path.read("/svc/dest/hello.world.com/80"))
      case id => fail(s"unexpected response ${id}")
    }
  }

  test("forwards requests if host doesn't match any route-rules") {
    val req = FRequest()
    req.host = "linkerd-fan.default:8080"
    await(identifier(req)) match {
      case IdentifiedRequest(Dst.Path(name, base, local), req1) =>
        assert(name == Path.read("/svc/dest/linkerd-fan.default/8080"))
      case id => fail(s"unexpected response ${id}")
    }
  }

  test("uses route-rule names for identification") {
    val req = FRequest()
    req.host = "bird-watcher.default.svc.cluster"
    await(identifier(req)) match {
      case IdentifiedRequest(Dst.Path(name, base, local), req1) =>
        assert(name == Path.read("/svc/route/bird-watcher-default/binoculars"))
      case id => fail(s"unexpected response ${id}")
    }
  }

  test("uses route-rule with highest precedence") {
    val req = FRequest()
    req.host = "poker.default.svc.cluster.local:8080"
    await(identifier(req)) match {
      case IdentifiedRequest(Dst.Path(name, base, local), req1) =>
        assert(name == Path.read("/svc/route/straight-flush/call"))
      case id => fail(s"unexpected response ${id}")
    }
  }
}
