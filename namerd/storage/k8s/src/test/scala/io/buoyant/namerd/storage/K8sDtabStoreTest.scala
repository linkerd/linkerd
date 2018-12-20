package io.buoyant.namerd.storage

import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.finagle.{Dtab, Http, Service}
import com.twitter.io.Buf
import com.twitter.util.{Activity, Future}
import io.buoyant.namerd.VersionedDtab
import io.buoyant.test.FunSuite
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class K8sDtabStoreTest extends FunSuite {
  val validAPIResponse = """
                {
   "apiVersion":"l5d.io/v1alpha1",
   "items":[
      {
         "apiVersion":"l5d.io/v1alpha1",
         "dentries":[
            {
               "dst":"/#/io.l5d.consul/dc1",
               "prefix":"/svc"
            },
            {
               "dst":"/$/inet/127.1/9991",
               "prefix":"/svc/srv"
            }
         ],
         "kind":"DTab",
         "metadata":{
            "annotations":null,
            "clusterName":"",
            "creationTimestamp":"2018-12-20T01:17:35Z",
            "generateName":null,
            "generation":1,
            "labels":null,
            "name":"secondary",
            "namespace":"default",
            "resourceVersion":"53333",
            "selfLink":"/apis/l5d.io/v1alpha1/namespaces/default/dtabs/secondary",
            "uid":"08784774-03f5-11e9-9f72-080027349b04"
         }
      }
   ],
   "kind":"DTabList",
   "metadata":{
      "continue":"",
      "resourceVersion":"53337",
      "selfLink":"/apis/l5d.io/v1alpha1/namespaces/default/dtabs"
   }
}
             """


  val initialEmptyResponse = """
    {
   "apiVersion":"l5d.io/v1alpha1",
   "items":[],
   "kind":"DTabList",
   "metadata":{
      "continue":"",
      "resourceVersion":"44673",
      "selfLink":"/apis/l5d.io/v1alpha1/namespaces/default/dtabs"
   }
}
  """.stripMargin

  val stepCount = new AtomicInteger()
  val server = Http.server.serve(
    ":*", Service.mk[Request, Response] { req =>
      stepCount.getAndIncrement match {
        case 0 =>
          val rep = Response(req.version, Status.Ok)
          rep.contentString = initialEmptyResponse
          Future.value(rep)
        case 1 =>
          val rep = Response(req.version, Status.NotFound)
          Future.value(rep)
        case _ =>
          val rep = Response(req.version, Status.Ok)
          rep.contentString = validAPIResponse
          Future.value(rep)
      }
    }
  )


  test("dtabstore should not stop watching dtab on 404 error") {
    @volatile var states: Activity.State[Option[VersionedDtab]] = Activity.Pending
    val store = new K8sDtabStore(
      Http.client,
      s"localhost:${server.boundAddress.asInstanceOf[InetSocketAddress].getPort}",
      "default"
    )

    val act = store.observe("secondary")
    act.run.changes.respond(states = _)

    eventually {
      val expected = Activity.Ok(
        Some(
          VersionedDtab(
            Dtab.read("/svc=>/#/io.l5d.consul/dc1;/svc/srv=>/$/inet/127.1/9991"),
            Buf.Utf8("53333")
          )
        )
      )
      assert(states == expected)
    }
  }
}
