package io.buoyant.k8s.istio

import com.twitter.conversions.time._
import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response}
import com.twitter.util.Future
import io.buoyant.k8s.istio.ApiserverClient.RouteRuleConfig
import io.buoyant.test.{Awaits, Exceptions}
import istio.proxy.v1.config.{DestinationWeight, RouteRule}
import org.scalatest.FunSuite

class ApiserverClientTest extends FunSuite with Awaits with Exceptions {

  val routeRulesList = s"""[
  {
   "type": "route-rule",
   "name": "details-default",
   "spec": {
    "destination": "details.default.svc.cluster.local",
    "precedence": 1,
    "route": [
     {
      "tags": {
       "version": "v1"
      }
     }
    ]
   }
  },
  {
   "type": "route-rule",
   "name": "productpage-default",
   "spec": {
    "destination": "productpage.default.svc.cluster.local",
    "precedence": 1,
    "route": [
     {
      "tags": {
       "version": "v1"
      }
     }
    ]
   }
  },
  {
   "type": "route-rule",
   "name": "reviews-default",
   "spec": {
    "destination": "reviews.default.svc.cluster.local",
    "precedence": 1,
    "route": [
     {
      "tags": {
       "version": "v1"
      }
     }
    ]
   }
  },
  {
   "type": "route-rule",
   "name": "ratings-default",
   "spec": {
    "destination": "ratings.default.svc.cluster.local",
    "precedence": 1,
    "route": [
     {
      "tags": {
       "version": "v1"
      }
     }
    ]
   }
  }
 ]"""

  val service = Service.mk[Request, Response] { req =>
    assert(req.uri == s"/v1alpha1/config/route-rule")
    val rsp = Response()
    rsp.contentString = routeRulesList
    Future.value(rsp)
  }

  test("getRouteRules() deserializes payload") {
    val pilotClient = new ApiserverClient(service, 5.seconds)
    val rsp = await(pilotClient.getRouteRules)
    assert(rsp.length == 4)
    assert(rsp.head == RouteRuleConfig(
      `type` = Some("route-rule"),
      name = Some("details-default"),
      spec = Some(RouteRule(
        `destination` = Some("details.default.svc.cluster.local"),
        `precedence` = Some(1),
        `route` = Seq(
          DestinationWeight(
            `tags` = Map(("version", "v1"))
          )
        )
      ))
    ))
  }

}
