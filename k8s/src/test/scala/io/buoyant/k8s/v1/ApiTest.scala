package io.buoyant.k8s.v1

import com.twitter.concurrent.AsyncQueue
import java.util.concurrent.ConcurrentLinkedQueue

import com.twitter.finagle.{Service => FService}
import com.twitter.finagle.http._
import com.twitter.io.Buf
import com.twitter.util._
import io.buoyant.k8s.{ObjectMeta, ObjectReference, Watchable}
import io.buoyant.test.{Awaits, Exceptions}
import org.scalatest.exceptions.TestFailedException
import org.scalatest.{FunSuite, Inside, OptionValues}

class ApiTest extends FunSuite
  with Awaits
  with Exceptions
  with Inside
  with OptionValues {

  val modified0 = Buf.Utf8("""{"type":"MODIFIED","object":{"kind":"Endpoints","apiVersion":"v1","metadata":{"name":"io","namespace":"buoy","selfLink":"/api/v1/namespaces/buoy/endpoints/io","uid":"625ecb50-3aea-11e5-bf6b-42010af087d8","resourceVersion":"4602708","creationTimestamp":"2015-08-04T20:50:05Z"},"subsets":[{"addresses":[{"ip":"10.248.2.8","targetRef":{"kind":"Pod","namespace":"buoy","name":"io-42wnm","uid":"79a3d50c-4dc7-11e5-9859-42010af01815","resourceVersion":"4502705"}},{"ip":"10.248.7.10","targetRef":{"kind":"Pod","namespace":"buoy","name":"io-csb9m","uid":"79a3b947-4dc7-11e5-9859-42010af01815","resourceVersion":"4502707"}},{"ip":"10.248.8.8","targetRef":{"kind":"Pod","namespace":"buoy","name":"io-7oj63","uid":"79a3af61-4dc7-11e5-9859-42010af01815","resourceVersion":"4502703"}}],"ports":[{"name":"router","port":4140,"protocol":"TCP"},{"name":"frontend","port":8080,"protocol":"TCP"}]}]}}""")
  val deleted0 = Buf.Utf8("""{"type":"DELETED","object":{"kind":"Endpoints","apiVersion":"v1","metadata":{"name":"io","namespace":"buoy","selfLink":"/api/v1/namespaces/buoy/endpoints/io","uid":"625ecb50-3aea-11e5-bf6b-42010af087d8","resourceVersion":"4602708","creationTimestamp":"2015-08-04T20:50:05Z"},"subsets":[{"addresses":[{"ip":"10.248.2.8","targetRef":{"kind":"Pod","namespace":"buoy","name":"io-42wnm","uid":"79a3d50c-4dc7-11e5-9859-42010af01815","resourceVersion":"4502705"}},{"ip":"10.248.7.10","targetRef":{"kind":"Pod","namespace":"buoy","name":"io-csb9m","uid":"79a3b947-4dc7-11e5-9859-42010af01815","resourceVersion":"4502707"}},{"ip":"10.248.8.8","targetRef":{"kind":"Pod","namespace":"buoy","name":"io-7oj63","uid":"79a3af61-4dc7-11e5-9859-42010af01815","resourceVersion":"4502703"}}],"ports":[{"name":"router","port":4140,"protocol":"TCP"},{"name":"frontend","port":8080,"protocol":"TCP"}]}]}}""")
  val added0 = Buf.Utf8("""{"type":"ADDED","object":{"kind":"Endpoints","apiVersion":"v1","metadata":{"name":"kubernetes","namespace":"default","selfLink":"/api/v1/namespaces/default/endpoints/kubernetes","uid":"9f84ade1-242a-11e5-a145-42010af0faf2","resourceVersion":"4502710","creationTimestamp":"2015-07-06T22:01:58Z"},"subsets":[{"addresses":[{"ip":"104.154.78.240"}],"ports":[{"port":443,"protocol":"TCP"}]}]}}""")
  val modified1 = Buf.Utf8("""{"type":"MODIFIED","object":{"kind":"Endpoints","apiVersion":"v1","metadata":{"name":"kube-dns","namespace":"kube-system","selfLink":"/api/v1/namespaces/kube-system/endpoints/kube-dns","uid":"a2952670-2a81-11e5-9cdb-42010af03dd2","resourceVersion":"4527454","creationTimestamp":"2015-07-14T23:39:57Z","labels":{"k8s-app":"kube-dns","kubernetes.io/cluster-service":"true","kubernetes.io/name":"KubeDNS"}},"subsets":[{"addresses":[{"ip":"10.248.3.3","targetRef":{"kind":"Pod","namespace":"kube-system","name":"kube-dns-v8-9ei0c","uid":"798a67f2-483a-11e5-9859-42010af01815","resourceVersion":"4427453"}}],"ports":[{"name":"dns-tcp","port":53,"protocol":"TCP"},{"name":"dns","port":53,"protocol":"UDP"}]}]}}""")
  val modified2 = Buf.Utf8("""{"type":"MODIFIED","object":{"kind":"Endpoints","apiVersion":"v1","metadata":{"name":"io","namespace":"buoy","selfLink":"/api/v1/namespaces/buoy/endpoints/io","uid":"625ecb50-3aea-11e5-bf6b-42010af087d8","resourceVersion":"4502709","creationTimestamp":"2015-08-04T20:50:05Z"},"subsets":[{"notReadyAddresses":[{"ip":"10.248.2.8","targetRef":{"kind":"Pod","namespace":"buoy","name":"io-42wnm","uid":"79a3d50c-4dc7-11e5-9859-42010af01815","resourceVersion":"4502705"}},{"ip":"10.248.7.10","targetRef":{"kind":"Pod","namespace":"buoy","name":"io-csb9m","uid":"79a3b947-4dc7-11e5-9859-42010af01815","resourceVersion":"4502707"}},{"ip":"10.248.8.8","targetRef":{"kind":"Pod","namespace":"buoy","name":"io-7oj63","uid":"79a3af61-4dc7-11e5-9859-42010af01815","resourceVersion":"4502703"}}],"ports":[{"name":"router","port":4140,"protocol":"TCP"},{"name":"frontend","port":8080,"protocol":"TCP"}]}]}}""")
  val modified3 = Buf.Utf8("""{"type":"MODIFIED","object":{"kind":"Endpoints","apiVersion":"v1","metadata":{"name":"io","namespace":"buoy","selfLink":"/api/v1/namespaces/buoy/endpoints/io","uid":"625ecb50-3aea-11e5-bf6b-42010af087d8","resourceVersion":"4602709","creationTimestamp":"2015-08-04T20:50:05Z"},"subsets":[{"addresses":[{"ip":"10.248.2.8","targetRef":{"kind":"Pod","namespace":"buoy","name":"io-42wnm","uid":"79a3d50c-4dc7-11e5-9859-42010af01815","resourceVersion":"4502705"}},{"ip":"10.248.7.10","targetRef":{"kind":"Pod","namespace":"buoy","name":"io-csb9m","uid":"79a3b947-4dc7-11e5-9859-42010af01815","resourceVersion":"4502707"}},{"ip":"10.248.8.8","targetRef":{"kind":"Pod","namespace":"buoy","name":"io-7oj63","uid":"79a3af61-4dc7-11e5-9859-42010af01815","resourceVersion":"4502703"}}],"ports":[{"name":"router","port":4140,"protocol":"TCP"},{"name":"frontend","port":8080,"protocol":"TCP"}]}]}}""")

  val endpoints0 = Buf.Utf8("""{"kind":"Endpoints","apiVersion":"v1","metadata":{"name":"accounts","namespace":"srv","selfLink":"/api/v1/namespaces/srv/endpoints/accounts","uid":"1b0e7393-4d10-11e5-9859-42010af01815","resourceVersion":"4430527","creationTimestamp":"2015-08-27T23:05:27Z"},"subsets":[{"addresses":[{"ip":"10.248.1.6","targetRef":{"kind":"Pod","namespace":"srv","name":"accounts-63f7n","uid":"1a91058a-4d10-11e5-9859-42010af01815","resourceVersion":"4430526"}},{"ip":"10.248.5.6","targetRef":{"kind":"Pod","namespace":"srv",""")
  val endpoints1 = Buf.Utf8(""""name":"accounts-rvgf2","uid":"1a90f98c-4d10-11e5-9859-42010af01815","resourceVersion":"4430498"}},{"ip":"10.248.8.6","targetRef":{"kind":"Pod","namespace":"srv","name":"accounts-is3is","uid":"1a90eac3-4d10-11e5-9859-42010af01815","resourceVersion":"4430524"}}],"ports":[{"name":"http","port":8086,"protocol":"TCP"}]}]}""")
  val endpointsList = Buf.Utf8("""{"kind": "EndpointsList","apiVersion": "v1","metadata": {"selfLink": "/api/v1/namespaces/greg-test/endpoints","resourceVersion": "17575669"},"items": [{"metadata": {"name": "accounts","namespace": "greg-test","selfLink": "/api/v1/namespaces/greg-test/endpoints/accounts","uid": "763fe50e-8d71-11e5-a206-42010af0004d","resourceVersion": "17147786","creationTimestamp": "2015-11-17T21:23:35Z"},"subsets": [{"addresses": [{"ip": "10.248.9.109","targetRef": {"kind": "Pod","namespace": "greg-test","name": "accounts-h5zht","uid": "0b598c6e-9f9b-11e5-94e8-42010af00045","resourceVersion": "17147785"}}],"ports": [{"name": "josh","port": 8080,"protocol": "TCP"}]}]},{"metadata": {"name": "auth","namespace": "greg-test","selfLink": "/api/v1/namespaces/greg-test/endpoints/auth","uid": "772cde01-8d71-11e5-a206-42010af0004d","resourceVersion": "17147808","creationTimestamp": "2015-11-17T21:23:37Z"},"subsets": [{"addresses": [{"ip": "10.248.4.134","targetRef": {"kind": "Pod","namespace": "greg-test","name": "auth-54q3e","uid": "0d5d0a2d-9f9b-11e5-94e8-42010af00045","resourceVersion": "17147807"}}],"ports": [{"name": "josh","port": 8080,"protocol": "TCP"}]}]},{"metadata": {"name": "empty-subset","namespace": "greg-test","selfLink": "/api/v1/namespaces/greg-test/endpoints/empty-subset","uid": "872cde01-8d71-11e5-a206-42010af0004d","resourceVersion": "27147808","creationTimestamp": "2016-11-17T21:23:37Z"},"subsets": null}]}""")

  test("namespace: get endpoints") {
    @volatile var reqCount = 0
    @volatile var failure: Throwable = null
    val service = FService.mk[Request, Response] { req =>
      reqCount += 1
      reqCount match {
        case 1 =>
          try {
            assert(req.uri == s"/api/v1/namespaces/srv/endpoints/accounts")
            val rsp = Response()
            rsp.version = req.version
            rsp.setContentTypeJson()
            rsp.headerMap("Transfer-Encoding") = "chunked"
            rsp.writer.write(endpoints0) before rsp.writer.write(endpoints1) before rsp.writer.close()
            Future.value(rsp)
          } catch {
            case e: Throwable =>
              failure = e
              Future.exception(e)
          }

        case _ => Future.never
      }
    }

    val ns = Api(service).withNamespace("srv")
    val endpoints = await(ns.endpoints.named("accounts").get()).value
    assert(endpoints == Endpoints(
      subsets = Some(Seq(
        EndpointSubset(
          addresses = Some(Seq(
            EndpointAddress("10.248.1.6", None, Some(ObjectReference(
              kind = Some("Pod"),
              namespace = Some("srv"),
              name = Some("accounts-63f7n"),
              uid = Some("1a91058a-4d10-11e5-9859-42010af01815"),
              resourceVersion = Some("4430526")
            ))),
            EndpointAddress("10.248.5.6", None, Some(ObjectReference(
              kind = Some("Pod"),
              namespace = Some("srv"),
              name = Some("accounts-rvgf2"),
              uid = Some("1a90f98c-4d10-11e5-9859-42010af01815"),
              resourceVersion = Some("4430498")
            ))),
            EndpointAddress("10.248.8.6", None, Some(ObjectReference(
              kind = Some("Pod"),
              namespace = Some("srv"),
              name = Some("accounts-is3is"),
              uid = Some("1a90eac3-4d10-11e5-9859-42010af01815"),
              resourceVersion = Some("4430524")
            )))
          )),
          ports = Some(Seq(
            EndpointPort(8086, Some("http"), Some("TCP"))
          ))
        )
      )),
      kind = Some("Endpoints"),
      apiVersion = Some("v1"),
      metadata = Some(ObjectMeta(
        name = Some("accounts"),
        namespace = Some("srv"),
        selfLink = Some("/api/v1/namespaces/srv/endpoints/accounts"),
        uid = Some("1b0e7393-4d10-11e5-9859-42010af01815"),
        resourceVersion = Some("4430527"),
        creationTimestamp = Some("2015-08-27T23:05:27Z")
      ))
    ))
    if (failure != null) throw failure
  }

  /**
   * Get an AsyncQueue of events from a watchable
   */
  private def watch[T <: Watchable[_, _, _]](
    watchable: T,
    resourceVersion: Option[String] = None
  ): (AsyncQueue[Activity.State[Any]], Closable) = {
    val events = new AsyncQueue[Activity.State[Any]]
    val closable = watchable.watch(
      resourceVersion = resourceVersion,
      state = value => { val _ = events.offer(value) }
    )
    (events, closable)
  }

  test("watch endpoint list: one message") {
    val chunk = new Promise[Buf]
    val rsp = Response()
    @volatile var failure: Throwable = null
    @volatile var reqCount = 0
    val service = FService.mk[Request, Response] { req =>
      reqCount += 1
      try {
        reqCount match {
          case 1 =>
            assert(req.uri == "/api/v1/watch/namespaces/srv/endpoints?resourceVersion=1234567")
            val rsp = Response()
            rsp.version = req.version
            chunk.flatMap(rsp.writer.write)
            Future.value(rsp)
          case _ => Future.never
        }
      } catch {
        case up: TestFailedException => throw up
        case e: Throwable =>
          failure = e
          Future.exception(e)
      }
    }
    val api = Api(service)

    val (events, closable) = watch(api.withNamespace("srv").endpoints, resourceVersion = Some("1234567"))
    assert(events.size == 0)
    chunk.setValue(modified0)
    await(events.poll())
    assert(events.size == 0)
    closable.close()
    if (failure != null) throw failure
  }

  test("watch endpoint list") {
    val rsp = Response()
    @volatile var reqCount = 0
    @volatile var failure: Throwable = null
    val service = FService.mk[Request, Response] { req =>
      reqCount += 1
      reqCount match {
        case 1 =>
          try {
            assert(req.path == "/api/v1/watch/namespaces/srv/endpoints")
            assert(req.params.getBoolean("watch").isEmpty)
            rsp.version = req.version
            Future.value(rsp)
          } catch {
            case up: TestFailedException => throw up
            case e: Throwable =>
              failure = e
              Future.exception(e)
          }
        case _ => Future.never
      }
    }
    val api = Api(service)

    val (events, closable) = watch(api.withNamespace("srv").endpoints, resourceVersion = Some("1234567"))
    val w = rsp.writer
    await(w.write(modified2 concat added0))

    {
      val Activity.Ok(EndpointsModified(eps)) = await(events.poll())
      assert(
        eps.subsets.get.flatMap(_.notReadyAddresses).flatten.map(_.ip) ==
          Seq("10.248.2.8", "10.248.7.10", "10.248.8.8")
      )
    }

    {
      val Activity.Ok(EndpointsAdded(eps)) = await(events.poll())
      assert(
        eps.subsets.get.flatMap(_.addresses).flatten.map(_.ip) ==
          Seq("104.154.78.240")
      )
    }

    assert(events.size == 0)
    await(w.write(modified1))

    {
      val Activity.Ok(EndpointsModified(eps)) = await(events.poll())
      assert(eps.subsets.get.flatMap(_.addresses).flatten.map(_.ip) ==
        Seq("10.248.3.3"))
    }

    assert(events.size == 0)
    await(w.write(modified0))

    {
      val Activity.Ok(EndpointsModified(eps)) = await(events.poll())
      assert(eps.subsets.get.flatMap(_.addresses).flatten.map(_.ip) ==
        Seq("10.248.2.8", "10.248.7.10", "10.248.8.8"))
    }
    closable.close()
    if (failure != null) throw failure
  }

  test("watch error") {
    val ver = "4659253"
    @volatile var reqCount = 0
    @volatile var failure: Throwable = null
    val service = FService.mk[Request, Response] { req =>
      reqCount += 1
      try {
        reqCount match {
          case 1 =>
            assert(req.uri == s"/api/v1/watch/namespaces/srv/endpoints?resourceVersion=$ver")
            val rsp = Response()
            rsp.version = req.version
            val msg = Buf.Utf8("""{"type":"ERROR","object":{"kind":"Status","apiVersion":"v1","metadata":{},"status":"Failure","message":"401: The event in requested index is outdated and cleared (the requested history has been cleared [4770862/4659254]) [4771861]"}}""")
            rsp.writer.write(msg).ensure {
              val _ = rsp.writer.close()
            }
            Future.value(rsp)
          case 2 =>
            assert(req.uri == "/api/v1/namespaces/srv/endpoints")
            val rsp = Response()
            rsp.version = req.version
            rsp.content = endpointsList
            Future.value(rsp)
          case 3 =>
            assert(req.uri == "/api/v1/watch/namespaces/srv/endpoints?resourceVersion=17575669") // this is the top-level resource version

            Future.never

          case _ =>
            Future.never
        }
      } catch {
        case up: TestFailedException => throw up
        case e: Throwable =>
          failure = e
          Future.exception(e)
      }
    }
    val api = Api(service)

    val (events, closable) = watch(api.withNamespace("srv").endpoints, resourceVersion = Some(ver))

    {
      val Activity.Ok(EndpointsError(status)) = await(events.poll())
      assert(status.status == Some("Failure"))
    }

    {
      val Activity.Ok(EndpointsModified(mod)) = await(events.poll())
      assert(mod.metadata.get.resourceVersion.contains("17147786"))
      assert(mod.subsets.get.head.addresses.contains(Seq(EndpointAddress("10.248.9.109", None, Some(ObjectReference(Some("Pod"), Some("greg-test"), Some("accounts-h5zht"), Some("0b598c6e-9f9b-11e5-94e8-42010af00045"), None, Some("17147785"), None))))))
    }

    {
      val Activity.Ok(EndpointsModified(mod)) = await(events.poll())
      assert(mod.metadata.get.resourceVersion.contains("17147808"))
      assert(mod.subsets.get.head.addresses.contains(List(EndpointAddress("10.248.4.134", None, Some(ObjectReference(Some("Pod"), Some("greg-test"), Some("auth-54q3e"), Some("0d5d0a2d-9f9b-11e5-94e8-42010af00045"), None, Some("17147807"), None))))))
    }

    {
      val Activity.Ok(EndpointsModified(mod)) = await(events.poll())
      assert(mod.metadata.get.resourceVersion.contains("27147808"))
      assert(mod.subsets.isEmpty)
    }

    assert(events.size == 0)
    closable.close()
    if (failure != null) throw failure
  }

  test("watch close") {
    val rsp = Response()
    @volatile var reqCount = 0
    @volatile var failure: Throwable = null
    val service = FService.mk[Request, Response] { req =>
      reqCount += 1
      reqCount match {
        case 1 =>
          try {
            assert(req.uri == "/api/v1/watch/namespaces/srv/endpoints")
            Future.value(rsp)
          } catch {
            case up: TestFailedException => throw up
            case e: Throwable =>
              failure = e
              Future.exception(e)
          }
        case _ => Future.never
      }
    }
    val api = Api(service)

    val (events, closable) = watch(api.withNamespace("srv").endpoints)

    assert(events.size == 0)

    await(rsp.writer.write(modified0))
    await(events.poll())
    assert(events.size == 0)
    assert(reqCount == 1)
    closable.close()
    if (failure != null) throw failure
  }

  test("watch too old") {
    val ver = "4659253"
    @volatile var reqCount = 0
    @volatile var failure: Throwable = null
    val service = FService.mk[Request, Response] { req =>
      reqCount += 1
      try {
        reqCount match {
          case 1 =>
            assert(req.uri == s"/api/v1/watch/namespaces/srv/endpoints?resourceVersion=$ver")
            val rsp = Response()
            rsp.version = req.version
            rsp.status = Status.Gone
            val msg = Buf.Utf8("""{"type":"ERROR","object":{"kind":"Status","apiVersion":"v1","metadata":{},"status":"Failure","message":"too old resource version: 62033480 (62353773)","reason":"Gone","code":410}}""")
            rsp.writer.write(msg).ensure {
              val _ = rsp.writer.close()
            }
            Future.value(rsp)
          case 2 =>
            assert(req.uri == "/api/v1/namespaces/srv/endpoints")
            val rsp = Response()
            rsp.version = req.version
            rsp.content = endpointsList
            Future.value(rsp)
          case 3 =>
            assert(req.uri == "/api/v1/watch/namespaces/srv/endpoints?resourceVersion=17575669") // this is the top-level resource version

            Future.never

          case _ => // ignore
            Future.never
        }
      } catch {
        case up: TestFailedException => throw up
        case e: Throwable =>
          failure = e
          Future.exception(e)
      }
    }
    val api = Api(service)

    val (events, closable) = watch(api.withNamespace("srv").endpoints, resourceVersion = Some(ver))

    {
      val Activity.Ok(EndpointsModified(mod)) = await(events.poll())
      assert(mod.metadata.get.resourceVersion.contains("17147786"))
      assert(mod.subsets.get.head.addresses.contains(Seq(EndpointAddress("10.248.9.109", None, Some(ObjectReference(Some("Pod"), Some("greg-test"), Some("accounts-h5zht"), Some("0b598c6e-9f9b-11e5-94e8-42010af00045"), None, Some("17147785"), None))))))
    }

    {
      val Activity.Ok(EndpointsModified(mod)) = await(events.poll())
      assert(mod.metadata.get.resourceVersion.contains("17147808"))
      assert(mod.subsets.get.head.addresses.contains(List(EndpointAddress("10.248.4.134", None, Some(ObjectReference(Some("Pod"), Some("greg-test"), Some("auth-54q3e"), Some("0d5d0a2d-9f9b-11e5-94e8-42010af00045"), None, Some("17147807"), None))))))
    }

    {
      val Activity.Ok(EndpointsModified(mod)) = await(events.poll())
      assert(mod.metadata.get.resourceVersion.contains("27147808"))
      assert(mod.subsets.isEmpty)
    }
    closable.close()
    if (failure != null) throw failure
  }

  test("watch too old with incorrect status code (kubernetes#35068)") {
    // reproduction for Linkerd issue #1636, caused by Kubernetes issue #35068.
    val ver = "4659253"
    @volatile var reqCount = 0
    @volatile var failure: Throwable = null
    val service = FService.mk[Request, Response] { req =>
      reqCount += 1
      try {
        reqCount match {
          case 1 =>
            assert(req.uri == s"/api/v1/watch/namespaces/srv/endpoints?resourceVersion=$ver")
            val rsp = Response()
            rsp.version = req.version
            rsp.status = Status.Ok
            val msg = Buf.Utf8("""{"type":"ERROR","object":{"kind":"Status","apiVersion":"v1","metadata":{},"status":"Failure","message":"too old resource version: 62033480 (62353773)","reason":"Gone","code":410}}""")
            rsp.writer.write(msg).ensure {
              val _ = rsp.writer.close()
            }
            Future.value(rsp)
          case 2 =>
            assert(req.uri == "/api/v1/namespaces/srv/endpoints")
            val rsp = Response()
            rsp.version = req.version
            rsp.content = endpointsList
            Future.value(rsp)
          case 3 =>
            assert(req.uri == "/api/v1/watch/namespaces/srv/endpoints?resourceVersion=17575669") // this is the top-level resource version

            Future.never

          case _ => // ignore
            Future.never
        }
      } catch {
        case up: TestFailedException => throw up
        case e: Throwable =>
          failure = e
          Future.exception(e)
      }
    }
    val api = Api(service)

    val (events, closable) = watch(api.withNamespace("srv").endpoints, resourceVersion = Some(ver))

    {
      val Activity.Ok(EndpointsModified(mod)) = await(events.poll())
      assert(mod.metadata.get.resourceVersion.contains("17147786"))
      assert(mod.subsets.get.head.addresses.contains(Seq(EndpointAddress("10.248.9.109", None, Some(ObjectReference(Some("Pod"), Some("greg-test"), Some("accounts-h5zht"), Some("0b598c6e-9f9b-11e5-94e8-42010af00045"), None, Some("17147785"), None))))))
    }

    {
      val Activity.Ok(EndpointsModified(mod)) = await(events.poll())
      assert(mod.metadata.get.resourceVersion.contains("17147808"))
      assert(mod.subsets.get.head.addresses.contains(List(EndpointAddress("10.248.4.134", None, Some(ObjectReference(Some("Pod"), Some("greg-test"), Some("auth-54q3e"), Some("0d5d0a2d-9f9b-11e5-94e8-42010af00045"), None, Some("17147807"), None))))))
    }

    {
      val Activity.Ok(EndpointsModified(mod)) = await(events.poll())
      assert(mod.metadata.get.resourceVersion.contains("27147808"))
      assert(mod.subsets.isEmpty)
    }
    closable.close()
    if (failure != null) throw failure
  }

  test("watch observes increasing resourceVersion only") {
    val rsp = Response()
    @volatile var reqCount = 0
    @volatile var failure: Throwable = null
    val service = FService.mk[Request, Response] { req =>
      reqCount += 1
      reqCount match {
        case 1 =>
          try {
            assert(req.path == "/api/v1/watch/namespaces/srv/endpoints")
            assert(req.params.getBoolean("watch").isEmpty)
            rsp.version = req.version
            Future.value(rsp)
          } catch {
            case up: TestFailedException => throw up
            case e: Throwable =>
              failure = e
              Future.exception(e)
          }
        case _ => Future.never
      }
    }
    val api = Api(service)

    val (events, closable) = watch(api.withNamespace("srv").endpoints, resourceVersion = Some("1234567"))
    val w = rsp.writer
    await(w.write(modified2 concat modified0))

    {
      val Activity.Ok(EndpointsModified(eps)) = await(events.poll())
      assert(eps.subsets.get.flatMap(_.notReadyAddresses).flatten.map(_.ip) ==
        Seq("10.248.2.8", "10.248.7.10", "10.248.8.8"))
    }

    {
      val Activity.Ok(EndpointsModified(eps)) = await(events.poll())
      assert(eps.subsets.get.flatMap(_.addresses).flatten.map(_.ip) ==
        Seq("10.248.2.8", "10.248.7.10", "10.248.8.8"))
    }

    assert(events.size == 0)

    // repeat the event: don't update, since resource version is the same
    await(w.write(modified0))
    assert(events.size == 0)

    // write an earlier event: no update since resource version is too low
    await(w.write(modified2))
    assert(events.size == 0)
    closable.close()
    if (failure != null) throw failure
  }


  test("watch ignores duplicate resourceVersion for non-DELETE events") {
    val rsp = Response()
    // this is the same event as modified2 but with the resource versions of modified1
    val modifiedDup = Buf.Utf8("""{"type":"MODIFIED","object":{"kind":"Endpoints","apiVersion":"v1","metadata":{"name":"io","namespace":"buoy","selfLink":"/api/v1/namespaces/buoy/endpoints/io","uid":"625ecb50-3aea-11e5-bf6b-42010af087d8","resourceVersion":"4602708","creationTimestamp":"2015-08-04T20:50:05Z"},"subsets":[{"notReadyAddresses":[{"ip":"10.248.2.8","targetRef":{"kind":"Pod","namespace":"buoy","name":"io-42wnm","uid":"79a3d50c-4dc7-11e5-9859-42010af01815","resourceVersion":"4502705"}},{"ip":"10.248.7.10","targetRef":{"kind":"Pod","namespace":"buoy","name":"io-csb9m","uid":"79a3b947-4dc7-11e5-9859-42010af01815","resourceVersion":"4502707"}},{"ip":"10.248.8.8","targetRef":{"kind":"Pod","namespace":"buoy","name":"io-7oj63","uid":"79a3af61-4dc7-11e5-9859-42010af01815","resourceVersion":"4502703"}}],"ports":[{"name":"router","port":4140,"protocol":"TCP"},{"name":"frontend","port":8080,"protocol":"TCP"}]}]}}""")
    @volatile var reqCount = 0
    @volatile var failure: Throwable = null
    val service = FService.mk[Request, Response] { req =>
      reqCount += 1
      reqCount match {
        case 1 =>
          try {
            assert(req.path == "/api/v1/watch/namespaces/srv/endpoints")
            assert(req.params.getBoolean("watch").isEmpty)
            rsp.version = req.version
            Future.value(rsp)
          } catch {
            case up: TestFailedException => throw up
            case e: Throwable =>
              failure = e
              Future.exception(e)
          }
        case _ => Future.never
      }
    }
    val api = Api(service)

    val (events, closable) = watch(api.withNamespace("srv").endpoints, resourceVersion = Some("1234567"))
    val w = rsp.writer

    assert(events.size == 0)

    // initial event
    await(w.write(modified0))
    withClue("after writing one event") {
      assert(events.size == 1)
    }

    // write an event with the same resource version: no update.
    await(w.write(modifiedDup))
    withClue("after writing two events with the same resource version") {
      assert(events.size == 1)
    }
    closable.close()
    if (failure != null) throw failure
  }

  test("watch allows DELETE events with the current resource version") {
    val rsp = Response()
    @volatile var reqCount = 0
    @volatile var failure: Throwable = null
    val service = FService.mk[Request, Response] { req =>
      reqCount += 1
      reqCount match {
        case 1 =>
          try {
            assert(req.path == "/api/v1/watch/namespaces/srv/endpoints")
            assert(req.params.getBoolean("watch").isEmpty)
            rsp.version = req.version
            Future.value(rsp)
          } catch {
            case up: TestFailedException => throw up
            case e: Throwable =>
              failure = e
              Future.exception(e)
          }
        case _ => Future.never
      }
    }
    val api = Api(service)
    val (events, closable) = watch(api.withNamespace("srv").endpoints, resourceVersion = Some("1234567"))
    val w = rsp.writer

    assert(events.size == 0)
    val resourceVersion1 = 4602708
    // initial event
    await(w.write(modified0))
    withClue("after writing first MODIFIED event") {
      assert(events.size == 1)

      val Activity.Ok(event: EndpointsWatch) = await(events.poll())
      assert(event.versionNum.value == resourceVersion1)
      val EndpointsModified(eps) = event
      assert(eps.subsets.get.flatMap(_.addresses).flatten.map(_.ip) ==
        Seq("10.248.2.8", "10.248.7.10", "10.248.8.8"))
    }

    assert(events.size == 0)


    // write a DELETE event with the same resource version
    await(w.write(deleted0))
    withClue("after writing first DELETE event") {
      assert(events.size == 1)

      val Activity.Ok(event: EndpointsWatch) = await(events.poll())
      assert(event.versionNum.value == resourceVersion1)
      val EndpointsDeleted(eps) = event
      assert(eps.subsets.get.flatMap(_.addresses).flatten.map(_.ip) ==
        Seq("10.248.2.8", "10.248.7.10", "10.248.8.8"))
    }

    // write an event with higher version than the delete event.
    await(w.write(modified3))
    withClue("after writing second MODIFIED event") {
      assert(events.size == 1)

      val Activity.Ok(event: EndpointsWatch) = await(events.poll())
      assert(event.versionNum.value == 4602709)
      val EndpointsModified(eps) = event
      assert(eps.subsets.get.flatMap(_.addresses).flatten.map(_.ip) ==
        Seq("10.248.2.8", "10.248.7.10", "10.248.8.8"))
    }

    // write DELETE event again. it should be ignored.
    await(w.write(deleted0))
    withClue("after writing first DELETE event again") {
      assert(events.size == 0)
    }
    closable.close()
    if (failure != null) throw failure
  }

  val configMap0 =
    """
      |{
      | "kind": "ConfigMap",
      | "apiVersion": "v1",
      | "metadata": {
      |   "name": "test-config",
      |   "namespace": "test"
      | },
      | "data": {
      |   "property-1": "my-great-value",
      |   "property-2": "help im trapped in a config map factory"
      | }
      |}
    """.stripMargin
  val configMap1 =
    """
      |{
      | "kind": "ConfigMap",
      | "apiVersion": "v1",
      | "metadata": {
      |   "name": "another-test-config",
      |   "namespace": "test
      | },
      | "data": {
      |   "object-property": {
      |     "key1": "value1"
      |     "key2": 1234
      |   },
      |   "array-property": [
      |     "foo", "bar", "baz", "quux"
      |   ]
      | }
      |}
    """.stripMargin
  val configMapList =
    s"""
      |{
      | "kind": "ConfigMapList",
      | "apiVersion": "v1",
      | "metadata": {
      |   "name": "test-configmap-list",
      |   "namespace": "test"
      | },
      | "items": [
      |   $configMap0,
      |   $configMap1
      | ]
      |}
    """.stripMargin
  test("namespace: get ConfigMap") {
    @volatile var reqCount = 0
    @volatile var failure: Throwable = null
    val service = FService.mk[Request, Response] { req =>
      reqCount += 1
      reqCount match {
        case 1 if req.uri == "/api/v1/namespaces/test/configmaps/test-config" =>
          try {
            val rsp = Response()
            rsp.version = req.version
            rsp.setContentTypeJson()
            rsp.headerMap("Transfer-Encoding") = "chunked"
            rsp.writer.write(Buf.Utf8(configMap0)) before rsp.writer.close()
            Future.value(rsp)
          } catch {
            case e: Throwable =>
              failure = e
              Future.exception(e)
          }
        case _ => Future.never
      }
    }

    val ns = Api(service).withNamespace("test")
    val configMap0Result = await(ns.configMap("test-config").get())
    inside(configMap0Result.value) {
      case ConfigMap(data, kind, metadata, apiVersion) =>
        assert(apiVersion.contains("v1"))
        assert(kind.contains("ConfigMap"))
        assert(metadata.value.name.contains("test-config"))
        assert(metadata.value.namespace.contains("test"))
        assert(data.get("property-1").contains("my-great-value"))
        assert(data.get("property-2").contains("help im trapped in a config map factory"))
    }
  }

  // currently ignored - rewriting `ConfigMap` to have arbitrary objects in `data` will take
  // a little work and doesn't seem to be actually necessary at the moment
  ignore("namespace: get complex ConfigMap") {
    @volatile var reqCount = 0
    @volatile var failure: Throwable = null
    val service = FService.mk[Request, Response] { req =>
      reqCount += 1
      reqCount match {
        case 1 if req.uri == "/api/v1/namespaces/test/configmaps/another-test-config" =>
          try {
            val rsp = Response()
            rsp.version = req.version
            rsp.setContentTypeJson()
            rsp.headerMap("Transfer-Encoding") = "chunked"
            rsp.writer.write(Buf.Utf8(configMap1)) before rsp.writer.close()
            Future.value(rsp)
          } catch {
            case e: Throwable =>
              failure = e
              Future.exception(e)
          }

        case _ => Future.never
      }
    }

    val ns = Api(service).withNamespace("test")
    val configMap1Result = await(ns.configMap("another-test-config").get()).value
    inside(configMap1Result) {
      case ConfigMap(data, kind, metadata, apiVersion) =>
        assert(apiVersion.contains("v1"))
        assert(kind.contains("ConfigMap"))
        assert(metadata.value.name.contains("another-test-config"))
        assert(metadata.value.namespace.contains("test"))
        // TODO: rewrite `ConfigMap` to try and parse these instead!
        assert(data.get("object-property").contains("""{"key1":"value1", "key2":1234}"""))
        assert(data.get("array-property").contains("""["foo","bar","baz","quux"]"""))
    }
  }
}
