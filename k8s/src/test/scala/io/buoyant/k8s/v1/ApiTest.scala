package io.buoyant.k8s.v1

import com.twitter.finagle.http._
import com.twitter.finagle.Service
import com.twitter.io.{Buf, Reader}
import com.twitter.util._
import io.buoyant.k8s.{ObjectMeta, ObjectReference}
import io.buoyant.test.{Exceptions, Awaits}
import org.scalatest.FunSuite

class ApiTest extends FunSuite with Awaits with Exceptions {

  val modified0 = Buf.Utf8("""{"type":"MODIFIED","object":{"kind":"Endpoints","apiVersion":"v1","metadata":{"name":"io","namespace":"buoy","selfLink":"/api/v1/namespaces/buoy/endpoints/io","uid":"625ecb50-3aea-11e5-bf6b-42010af087d8","resourceVersion":"4502708","creationTimestamp":"2015-08-04T20:50:05Z"},"subsets":[{"addresses":[{"ip":"10.248.2.8","targetRef":{"kind":"Pod","namespace":"buoy","name":"io-42wnm","uid":"79a3d50c-4dc7-11e5-9859-42010af01815","resourceVersion":"4502705"}},{"ip":"10.248.7.10","targetRef":{"kind":"Pod","namespace":"buoy","name":"io-csb9m","uid":"79a3b947-4dc7-11e5-9859-42010af01815","resourceVersion":"4502707"}},{"ip":"10.248.8.8","targetRef":{"kind":"Pod","namespace":"buoy","name":"io-7oj63","uid":"79a3af61-4dc7-11e5-9859-42010af01815","resourceVersion":"4502703"}}],"ports":[{"name":"router","port":4140,"protocol":"TCP"},{"name":"frontend","port":8080,"protocol":"TCP"}]}]}}""")
  val added0 = Buf.Utf8("""{"type":"ADDED","object":{"kind":"Endpoints","apiVersion":"v1","metadata":{"name":"kubernetes","namespace":"default","selfLink":"/api/v1/namespaces/default/endpoints/kubernetes","uid":"9f84ade1-242a-11e5-a145-42010af0faf2","resourceVersion":"7","creationTimestamp":"2015-07-06T22:01:58Z"},"subsets":[{"addresses":[{"ip":"104.154.78.240"}],"ports":[{"port":443,"protocol":"TCP"}]}]}}""")
  val modified1 = Buf.Utf8("""{"type":"MODIFIED","object":{"kind":"Endpoints","apiVersion":"v1","metadata":{"name":"kube-dns","namespace":"kube-system","selfLink":"/api/v1/namespaces/kube-system/endpoints/kube-dns","uid":"a2952670-2a81-11e5-9cdb-42010af03dd2","resourceVersion":"4427454","creationTimestamp":"2015-07-14T23:39:57Z","labels":{"k8s-app":"kube-dns","kubernetes.io/cluster-service":"true","kubernetes.io/name":"KubeDNS"}},"subsets":[{"addresses":[{"ip":"10.248.3.3","targetRef":{"kind":"Pod","namespace":"kube-system","name":"kube-dns-v8-9ei0c","uid":"798a67f2-483a-11e5-9859-42010af01815","resourceVersion":"4427453"}}],"ports":[{"name":"dns-tcp","port":53,"protocol":"TCP"},{"name":"dns","port":53,"protocol":"UDP"}]}]}}""")
  val modified2 = Buf.Utf8("""{"type":"MODIFIED","object":{"kind":"Endpoints","apiVersion":"v1","metadata":{"name":"io","namespace":"buoy","selfLink":"/api/v1/namespaces/buoy/endpoints/io","uid":"625ecb50-3aea-11e5-bf6b-42010af087d8","resourceVersion":"4502708","creationTimestamp":"2015-08-04T20:50:05Z"},"subsets":[{"notReadyAddresses":[{"ip":"10.248.2.8","targetRef":{"kind":"Pod","namespace":"buoy","name":"io-42wnm","uid":"79a3d50c-4dc7-11e5-9859-42010af01815","resourceVersion":"4502705"}},{"ip":"10.248.7.10","targetRef":{"kind":"Pod","namespace":"buoy","name":"io-csb9m","uid":"79a3b947-4dc7-11e5-9859-42010af01815","resourceVersion":"4502707"}},{"ip":"10.248.8.8","targetRef":{"kind":"Pod","namespace":"buoy","name":"io-7oj63","uid":"79a3af61-4dc7-11e5-9859-42010af01815","resourceVersion":"4502703"}}],"ports":[{"name":"router","port":4140,"protocol":"TCP"},{"name":"frontend","port":8080,"protocol":"TCP"}]}]}}""")

  val endpoints0 = Buf.Utf8("""{"kind":"Endpoints","apiVersion":"v1","metadata":{"name":"accounts","namespace":"srv","selfLink":"/api/v1/namespaces/srv/endpoints/accounts","uid":"1b0e7393-4d10-11e5-9859-42010af01815","resourceVersion":"4430527","creationTimestamp":"2015-08-27T23:05:27Z"},"subsets":[{"addresses":[{"ip":"10.248.1.6","targetRef":{"kind":"Pod","namespace":"srv","name":"accounts-63f7n","uid":"1a91058a-4d10-11e5-9859-42010af01815","resourceVersion":"4430526"}},{"ip":"10.248.5.6","targetRef":{"kind":"Pod","namespace":"srv",""")
  val endpoints1 = Buf.Utf8(""""name":"accounts-rvgf2","uid":"1a90f98c-4d10-11e5-9859-42010af01815","resourceVersion":"4430498"}},{"ip":"10.248.8.6","targetRef":{"kind":"Pod","namespace":"srv","name":"accounts-is3is","uid":"1a90eac3-4d10-11e5-9859-42010af01815","resourceVersion":"4430524"}}],"ports":[{"name":"http","port":8086,"protocol":"TCP"}]}]}""")
  val endpointsList = Buf.Utf8("""{"kind": "EndpointsList","apiVersion": "v1","metadata":{"selfLink":"/api/v1/namespaces/greg-test/endpoints","resourceVersion": "17575669"},"items":[{"metadata":{"name": "accounts","namespace": "greg-test","selfLink": "/api/v1/namespaces/greg-test/endpoints/accounts","uid": "763fe50e-8d71-11e5-a206-42010af0004d","resourceVersion": "17147786","creationTimestamp": "2015-11-17T21:23:35Z"},"subsets":[{"addresses":[{"ip": "10.248.9.109","targetRef":{"kind":"Pod","namespace":"greg-test","name":"accounts-h5zht","uid": "0b598c6e-9f9b-11e5-94e8-42010af00045","resourceVersion": "17147785"}}],"ports":[{"name":"josh","port":8080,"protocol":"TCP"}]}]},{"metadata":{"name": "auth","namespace": "greg-test","selfLink": "/api/v1/namespaces/greg-test/endpoints/auth","uid": "772cde01-8d71-11e5-a206-42010af0004d","resourceVersion": "17147808","creationTimestamp": "2015-11-17T21:23:37Z"},"subsets":[{"addresses":[{"ip":"10.248.4.134","targetRef":{"kind": "Pod","namespace":"greg-test","name": "auth-54q3e","uid": "0d5d0a2d-9f9b-11e5-94e8-42010af00045","resourceVersion": "17147807"}}],"ports":[{"name":"josh","port":8080,"protocol":"TCP"}]}]}]}""")

  test("namespace: get endpoints") {
    val service = Service.mk[Request, Response] { req =>
      assert(req.uri == s"/api/v1/namespaces/srv/endpoints/accounts")
      val rsp = Response()
      rsp.version = req.version
      rsp.setContentTypeJson()
      rsp.headerMap("Transfer-Encoding") = "chunked"

      rsp.writer.write(endpoints0) before rsp.writer.write(endpoints1) before rsp.writer.close()

      Future.value(rsp)
    }

    val ns = Api(service).withNamespace("srv")
    val endpoints = await(ns.endpoints.named("accounts").get)
    assert(endpoints == Endpoints(
      subsets = Seq(
        EndpointSubset(
          addresses = Some(Seq(
            EndpointAddress("10.248.1.6", Some(ObjectReference(
              kind = Some("Pod"),
              namespace = Some("srv"),
              name = Some("accounts-63f7n"),
              uid = Some("1a91058a-4d10-11e5-9859-42010af01815"),
              resourceVersion = Some("4430526")
            ))),
            EndpointAddress("10.248.5.6", Some(ObjectReference(
              kind = Some("Pod"),
              namespace = Some("srv"),
              name = Some("accounts-rvgf2"),
              uid = Some("1a90f98c-4d10-11e5-9859-42010af01815"),
              resourceVersion = Some("4430498")
            ))),
            EndpointAddress("10.248.8.6", Some(ObjectReference(
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
      ),
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
  }

  test("watch endpoint list: one message") {
    val chunk = new Promise[Buf]
    val service = Service.mk[Request, Response] { req =>
      assert(req.uri == "/api/v1/endpoints?watch=true&resourceVersion=1234567")
      val rsp = Response()
      rsp.version = req.version
      chunk.flatMap(rsp.writer.write)
      Future.value(rsp)
    }
    val api = Api(service)

    val (stream, _) = api.endpoints.watch(resourceVersion = Some("1234567"))
    val hd = stream.head
    assert(!hd.isDefined)
    chunk.setValue(modified0)
    assert(hd.isDefined)
  }

  test("watch endpoint list") {
    val rsp = Response()
    val service = Service.mk[Request, Response] { req =>
      assert(req.path == "/api/v1/endpoints")
      assert(req.params.getBoolean("watch") == Some(true))
      rsp.version = req.version
      Future.value(rsp)
    }
    val api = Api(service)

    val (stream, closable) = api.endpoints.watch()
    val w = rsp.writer
    await(w.write(modified2 concat added0))
    await(stream.uncons) match {
      case Some((EndpointsWatch.Modified(eps), getStream)) =>
        assert(eps.subsets.flatMap(_.notReadyAddresses).flatten.map(_.ip) ==
          Seq("10.248.2.8", "10.248.7.10", "10.248.8.8"))

        await(getStream().uncons) match {
          case Some((EndpointsWatch.Added(eps), getStream)) =>
            assert(eps.subsets.flatMap(_.addresses).flatten.map(_.ip) ==
              Seq("104.154.78.240"))

            val next = getStream().uncons
            assert(!next.isDefined)
            await(w.write(modified1))
            assert(next.isDefined)
            await(next) match {
              case Some((EndpointsWatch.Modified(eps), getStream)) =>
                assert(eps.subsets.flatMap(_.addresses).flatten.map(_.ip) ==
                  Seq("10.248.3.3"))

                val next = getStream().uncons
                assert(!next.isDefined)
                await(w.write(modified0))
                assert(next.isDefined)
                await(next) match {
                  case Some((EndpointsWatch.Modified(eps), getStream)) =>
                    assert(eps.subsets.flatMap(_.addresses).flatten.map(_.ip) ==
                      Seq("10.248.2.8", "10.248.7.10", "10.248.8.8"))
                    val next = getStream().uncons
                    await(closable.close())
                  case event =>
                    fail(s"unexpected event: $event")
                }
              case event =>
                fail(s"unexpected event: $event")
            }
          case event =>
            fail(s"unexpected event: $event")
        }
      case event =>
        fail(s"unexpected event: $event")
    }
  }

  test("watch error") {
    val ver = "4659253"
    var reqCount = 0
    var finalResponse: Response = null
    val service = Service.mk[Request, Response] { req =>
      reqCount += 1
      reqCount match {
        case 1 =>
          assert(req.uri == s"/api/v1/endpoints?watch=true&resourceVersion=$ver")
          val rsp = Response()
          rsp.version = req.version
          val msg = Buf.Utf8("""{"type":"ERROR","object":{"kind":"Status","apiVersion":"v1","metadata":{},"status":"Failure","message":"401: The event in requested index is outdated and cleared (the requested history has been cleared [4770862/4659254]) [4771861]"}}""")
          rsp.writer.write(msg).ensure {
            val _ = rsp.writer.close()
          }
          Future.value(rsp)
        case 2 =>
          assert(req.uri == "/api/v1/endpoints")
          val rsp = Response()
          rsp.version = req.version
          rsp.setContentTypeJson()
          rsp.headerMap("Transfer-Encoding") = "chunked"

          rsp.writer.write(endpointsList) before rsp.writer.close()
          Future.value(rsp)
        case 3 =>
          assert(req.uri == "/api/v1/endpoints?watch=true&resourceVersion=17575669") // this is the top-level resource version
          val rsp = Response()
          rsp.version = req.version
          rsp.setContentTypeJson()
          rsp.headerMap("Transfer-Encoding") = "chunked"
          finalResponse = rsp
          Future.value(rsp)

        case other =>
          fail(s"unexpected number of requests $other")
      }
    }
    val api = Api(service)

    val (stream, closable) = api.endpoints.watch(resourceVersion = Some(ver))
    await(stream.uncons) match {
      case Some((EndpointsWatch.Error(status), stream)) =>
        assert(status.status == Some("Failure"))
        await(stream().uncons) match {
          case Some((EndpointsWatch.Modified(mod), stream)) =>
            assert(mod.metadata.get.resourceVersion.get == "17147786")
            assert(mod.subsets.head.addresses == Some(Seq(EndpointAddress("10.248.9.109", Some(ObjectReference(Some("Pod"), Some("greg-test"), Some("accounts-h5zht"), Some("0b598c6e-9f9b-11e5-94e8-42010af00045"), None, Some("17147785"), None))))))
            await(stream().uncons) match {
              case Some((EndpointsWatch.Modified(mod), stream)) =>
                assert(mod.metadata.get.resourceVersion.get == "17147808")
                assert(mod.subsets.head.addresses == Some(List(EndpointAddress("10.248.4.134", Some(ObjectReference(Some("Pod"), Some("greg-test"), Some("auth-54q3e"), Some("0d5d0a2d-9f9b-11e5-94e8-42010af00045"), None, Some("17147807"), None))))))
                val next = stream().uncons
                await(closable.close())
                assertThrows[Reader.ReaderDiscarded] {
                  await(next)
                }

              case event =>
                fail(s"unexpected event: $event")
            }
          case event =>
            fail(s"unexpected event: $event")
        }
      case event =>
        fail(s"unexpected event: $event")
    }
  }

  test("watch close") {
    val rsp = Response()
    val service = Service.mk[Request, Response] { req =>
      assert(req.uri == "/api/v1/endpoints?watch=true")
      rsp.version = req.version
      Future.value(rsp)
    }
    val api = Api(service)

    val (stream, closer) = api.endpoints.watch()
    var uncons = stream.uncons
    assert(!uncons.isDefined)

    await(rsp.writer.write(modified0))
    uncons = await(uncons) match {
      case Some((_, rest)) => rest().uncons
      case None => fail("chunk not read")
    }
    assert(!uncons.isDefined)

    await {
      closer.close()
    }
    assert(uncons.isDefined)
    assertThrows[Reader.ReaderDiscarded] {
      await(uncons)
    }
    assertThrows[Reader.ReaderDiscarded] {
      await {
        rsp.writer.write(added0)
      }
    }
  }
}
