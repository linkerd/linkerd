package io.buoyant.k8s

import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response}
import com.twitter.io.{Buf, Writer}
import com.twitter.util.Promise
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class EndpointsNamerE2ETest extends FunSuite with Awaits {
  object Responses {
    val Initial = Buf.Utf8 (
      """{"kind":"Endpoints","apiVersion":"v1","metadata":{"name":"world-v1","namespace":"liza1626","selfLink":"/api/v1/namespaces/liza1626/endpoints/world-v1","uid":"38358c99-97c7-11e7-9078-42010af00004","resourceVersion":"61412986","creationTimestamp":"2017-09-12T14:32:35Z"},"subsets":[{"addresses":[{"ip":"10.196.5.84","nodeName":"gke-hosted-n1-standard-32-pool-3-c58b8cde-jpvz","targetRef":{"kind":"Pod","namespace":"liza1626","name":"world-v1-1mkr7","uid":"0d893850-9806-11e7-9078-42010af00004","resourceVersion":"61412979"}},{"ip":"10.196.5.85","nodeName":"gke-hosted-n1-standard-32-pool-3-c58b8cde-jpvz","targetRef":{"kind":"Pod","namespace":"liza1626","name":"world-v1-vd6dg","uid":"0d89460f-9806-11e7-9078-42010af00004","resourceVersion":"61412980"}},{"ip":"10.196.5.86","nodeName":"gke-hosted-n1-standard-32-pool-3-c58b8cde-jpvz","targetRef":{"kind":"Pod","namespace":"liza1626","name":"world-v1-z86bg","uid":"0d894db8-9806-11e7-9078-42010af00004","resourceVersion":"61412983"}}],"ports":[{"name":"http","port":7778,"protocol":"TCP"}]}]}"""
    )
    val InitialWatch = Buf.Utf8(
      """
        {"type":"ADDED","object":{"kind":"Endpoints","apiVersion":"v1","metadata":{"name":"world-v1","namespace":"liza1626","selfLink":"/api/v1/namespaces/liza1626/endpoints/world-v1","uid":"38358c99-97c7-11e7-9078-42010af00004","resourceVersion":"61412986","creationTimestamp":"2017-09-12T14:32:35Z"},"subsets":[{"addresses":[{"ip":"10.196.5.84","nodeName":"gke-hosted-n1-standard-32-pool-3-c58b8cde-jpvz","targetRef":{"kind":"Pod","namespace":"liza1626","name":"world-v1-1mkr7","uid":"0d893850-9806-11e7-9078-42010af00004","resourceVersion":"61412979"}},{"ip":"10.196.5.85","nodeName":"gke-hosted-n1-standard-32-pool-3-c58b8cde-jpvz","targetRef":{"kind":"Pod","namespace":"liza1626","name":"world-v1-vd6dg","uid":"0d89460f-9806-11e7-9078-42010af00004","resourceVersion":"61412980"}},{"ip":"10.196.5.86","nodeName":"gke-hosted-n1-standard-32-pool-3-c58b8cde-jpvz","targetRef":{"kind":"Pod","namespace":"liza1626","name":"world-v1-z86bg","uid":"0d894db8-9806-11e7-9078-42010af00004","resourceVersion":"61412983"}}],"ports":[{"name":"http","port":7778,"protocol":"TCP"}]}]}}
      """)
    val BeforeScaleDown = Buf.Utf8(
      """{"type":"MODIFIED","object":{"kind":"Endpoints","apiVersion":"v1","metadata":{"name":"world-v1","namespace":"liza1626","selfLink":"/api/v1/namespaces/liza1626/endpoints/world-v1","uid":"38358c99-97c7-11e7-9078-42010af00004","resourceVersion":"61492813","creationTimestamp":"2017-09-12T14:32:35Z"},"subsets":[{"addresses":[{"ip":"10.196.5.84","nodeName":"gke-hosted-n1-standard-32-pool-3-c58b8cde-jpvz","targetRef":{"kind":"Pod","namespace":"liza1626","name":"world-v1-1mkr7","uid":"0d893850-9806-11e7-9078-42010af00004","resourceVersion":"61412979"}},{"ip":"10.196.5.85","nodeName":"gke-hosted-n1-standard-32-pool-3-c58b8cde-jpvz","targetRef":{"kind":"Pod","namespace":"liza1626","name":"world-v1-vd6dg","uid":"0d89460f-9806-11e7-9078-42010af00004","resourceVersion":"61412980"}}],"ports":[{"name":"http","port":7778,"protocol":"TCP"}]}]}}"""
    )
    val ScaleDown = Buf.Utf8(
      """{"type":"MODIFIED","object":{"kind":"Endpoints","apiVersion":"v1","metadata":{"name":"world-v1","namespace":"liza1626","selfLink":"/api/v1/namespaces/liza1626/endpoints/world-v1","uid":"38358c99-97c7-11e7-9078-42010af00004","resourceVersion":"61492816","creationTimestamp":"2017-09-12T14:32:35Z"},"subsets":[]}}"""
    )
    val ScaleUp1 = Buf.Utf8(
      """{"type":"MODIFIED","object":{"kind":"Endpoints","apiVersion":"v1","metadata":{"name":"world-v1","namespace":"liza1626","selfLink":"/api/v1/namespaces/liza1626/endpoints/world-v1","uid":"38358c99-97c7-11e7-9078-42010af00004","resourceVersion":"61492931","creationTimestamp":"2017-09-12T14:32:35Z"},"subsets":[{"addresses":[{"ip":"10.196.5.87","nodeName":"gke-hosted-n1-standard-32-pool-3-c58b8cde-jpvz","targetRef":{"kind":"Pod","namespace":"liza1626","name":"world-v1-6x155","uid":"ef248ef2-988f-11e7-9078-42010af00004","resourceVersion":"61492928"}}],"ports":[{"name":"http","port":7778,"protocol":"TCP"}]}]}}"""
    )
    val ScaleUp2 = Buf.Utf8(
      """{"type":"MODIFIED","object":{"kind":"Endpoints","apiVersion":"v1","metadata":{"name":"world-v1","namespace":"liza1626","selfLink":"/api/v1/namespaces/liza1626/endpoints/world-v1","uid":"38358c99-97c7-11e7-9078-42010af00004","resourceVersion":"61492933","creationTimestamp":"2017-09-12T14:32:35Z"},"subsets":[{"addresses":[{"ip":"10.196.5.87","nodeName":"gke-hosted-n1-standard-32-pool-3-c58b8cde-jpvz","targetRef":{"kind":"Pod","namespace":"liza1626","name":"world-v1-6x155","uid":"ef248ef2-988f-11e7-9078-42010af00004","resourceVersion":"61492928"}},{"ip":"10.196.5.89","nodeName":"gke-hosted-n1-standard-32-pool-3-c58b8cde-jpvz","targetRef":{"kind":"Pod","namespace":"liza1626","name":"world-v1-whz9k","uid":"ef248a9c-988f-11e7-9078-42010af00004","resourceVersion":"61492930"}}],"ports":[{"name":"http","port":7778,"protocol":"TCP"}]}]}}"""
    )
    val ScaleUp3 = Buf.Utf8(
      """{"type":"MODIFIED","object":{"kind":"Endpoints","apiVersion":"v1","metadata":{"name":"world-v1","namespace":"liza1626","selfLink":"/api/v1/namespaces/liza1626/endpoints/world-v1","uid":"38358c99-97c7-11e7-9078-42010af00004","resourceVersion":"61492936","creationTimestamp":"2017-09-12T14:32:35Z"},"subsets":[{"addresses":[{"ip":"10.196.5.87","nodeName":"gke-hosted-n1-standard-32-pool-3-c58b8cde-jpvz","targetRef":{"kind":"Pod","namespace":"liza1626","name":"world-v1-6x155","uid":"ef248ef2-988f-11e7-9078-42010af00004","resourceVersion":"61492928"}},{"ip":"10.196.5.88","nodeName":"gke-hosted-n1-standard-32-pool-3-c58b8cde-jpvz","targetRef":{"kind":"Pod","namespace":"liza1626","name":"world-v1-w8qsk","uid":"ef249841-988f-11e7-9078-42010af00004","resourceVersion":"61492934"}},{"ip":"10.196.5.89","nodeName":"gke-hosted-n1-standard-32-pool-3-c58b8cde-jpvz","targetRef":{"kind":"Pod","namespace":"liza1626","name":"world-v1-whz9k","uid":"ef248a9c-988f-11e7-9078-42010af00004","resourceVersion":"61492930"}}],"ports":[{"name":"http","port":7778,"protocol":"TCP"}]}]}}"""
    )
  }
  trait Fixtures {

    @volatile var writer: Writer = null
    @volatile var doInit, didInit, doScaleUp, doScaleDown = new Promise[Unit]

    val service = Service.mk[Request, Response] {
      ???
    }
  }
}
