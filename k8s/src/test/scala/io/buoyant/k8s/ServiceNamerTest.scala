package io.buoyant.k8s

import com.twitter.conversions.DurationOps._
import com.twitter.finagle._
import com.twitter.finagle.http.{Request, Response, Status => HttpStatus}
import com.twitter.io.{Buf, Writer}
import com.twitter.util._
import io.buoyant.namer.RichActivity
import io.buoyant.test.Awaits
import java.net.InetSocketAddress
import org.scalatest.FunSuite
import org.scalatest.exceptions.TestFailedException

class ServiceNamerTest extends FunSuite with Awaits {

  object Rsps {
    val NotFound = Buf.Utf8(
      """
        |{
        |  "kind": "Status",
        |  "apiVersion": "v1",
        |  "metadata": {},
        |  "status": "Failure",
        |  "message": "services \"foo\" not found",
        |  "reason": "NotFound",
        |  "details": {
        |    "name": "foo",
        |    "kind": "services"
        |  },
        |  "code": 404
        |}
      """.stripMargin
    )
    val Init = Buf.Utf8(
      """
        |{
        |    "kind": "Service",
        |    "apiVersion": "v1",
        |    "metadata": {
        |      "name": "l5d",
        |      "namespace": "pythonsky",
        |      "selfLink": "/api/v1/namespaces/pythonsky/services/l5d",
        |      "uid": "53fb416f-8c0a-11e6-a2a5-42010af00004",
        |      "resourceVersion": "11597494",
        |      "creationTimestamp": "2016-10-06T21:17:46Z",
        |      "annotations": {
        |        "kubectl.kubernetes.io/last-applied-configuration": "{\"kind\":\"Service\",\"apiVersion\":\"v1\",\"metadata\":{\"name\":\"l5d\",\"creationTimestamp\":null},\"spec\":{\"ports\":[{\"name\":\"external\",\"port\":80,\"targetPort\":8080},{\"name\":\"outgoing\",\"port\":4140,\"targetPort\":0},{\"name\":\"incoming\",\"port\":4141,\"targetPort\":0},{\"name\":\"admin\",\"port\":9990,\"targetPort\":0}],\"selector\":{\"app\":\"l5d\"},\"type\":\"LoadBalancer\"},\"status\":{\"loadBalancer\":{}}}"
        |      }
        |    },
        |    "spec": {
        |      "ports": [
        |        {
        |          "name": "external",
        |          "protocol": "TCP",
        |          "port": 80,
        |          "targetPort": 8080,
        |          "nodePort": 32131
        |        },
        |        {
        |          "name": "outgoing",
        |          "protocol": "TCP",
        |          "port": 4140,
        |          "targetPort": 4140,
        |          "nodePort": 30735
        |        },
        |        {
        |          "name": "incoming",
        |          "protocol": "TCP",
        |          "port": 4141,
        |          "targetPort": 4141,
        |          "nodePort": 30261
        |        },
        |        {
        |          "name": "admin",
        |          "protocol": "TCP",
        |          "port": 9990,
        |          "targetPort": 9990,
        |          "nodePort": 31850
        |        }
        |      ],
        |      "selector": {
        |        "app": "l5d"
        |      },
        |      "clusterIP": "10.199.242.33",
        |      "type": "LoadBalancer",
        |      "sessionAffinity": "None"
        |    },
        |    "status": {
        |      "loadBalancer": {
        |        "ingress": [
        |          {
        |            "ip": "104.155.170.94"
        |          }
        |        ]
        |      }
        |    }
        |}
      """.stripMargin
    )
    val List = Buf.Utf8("""{ "kind": "ServiceList", "apiVersion": "v1", "metadata": { "selfLink": "/api/v1/namespaces/pythonsky/services", "resourceVersion": "11600623" }, "items": [ { "metadata": { "name": "hello", "namespace": "pythonsky", "selfLink": "/api/v1/namespaces/pythonsky/services/hello", "uid": "53b183a0-8c0a-11e6-a2a5-42010af00004", "resourceVersion": "11597405", "creationTimestamp": "2016-10-06T21:17:46Z", "annotations": { "kubectl.kubernetes.io/last-applied-configuration": "{\"kind\":\"Service\",\"apiVersion\":\"v1\",\"metadata\":{\"name\":\"hello\",\"creationTimestamp\":null},\"spec\":{\"ports\":[{\"name\":\"http\",\"port\":7000,\"targetPort\":0}],\"selector\":{\"app\":\"hello\"},\"clusterIP\":\"None\"},\"status\":{\"loadBalancer\":{}}}" } }, "spec": { "ports": [ { "name": "http", "protocol": "TCP", "port": 7000, "targetPort": 7000 } ], "selector": { "app": "hello" }, "clusterIP": "None", "type": "ClusterIP", "sessionAffinity": "None" }, "status": { "loadBalancer": {} } }, { "metadata": { "name": "l5d", "namespace": "pythonsky", "selfLink": "/api/v1/namespaces/pythonsky/services/l5d", "uid": "53fb416f-8c0a-11e6-a2a5-42010af00004", "resourceVersion": "11597494", "creationTimestamp": "2016-10-06T21:17:46Z", "annotations": { "kubectl.kubernetes.io/last-applied-configuration": "{\"kind\":\"Service\",\"apiVersion\":\"v1\",\"metadata\":{\"name\":\"l5d\",\"creationTimestamp\":null},\"spec\":{\"ports\":[{\"name\":\"external\",\"port\":80,\"targetPort\":8080},{\"name\":\"outgoing\",\"port\":4140,\"targetPort\":0},{\"name\":\"incoming\",\"port\":4141,\"targetPort\":0},{\"name\":\"admin\",\"port\":9990,\"targetPort\":0}],\"selector\":{\"app\":\"l5d\"},\"type\":\"LoadBalancer\"},\"status\":{\"loadBalancer\":{}}}" } }, "spec": { "ports": [ { "name": "external", "protocol": "TCP", "port": 80, "targetPort": 8080, "nodePort": 32131 }, { "name": "outgoing", "protocol": "TCP", "port": 4140, "targetPort": 4140, "nodePort": 30735 }, { "name": "incoming", "protocol": "TCP", "port": 4141, "targetPort": 4141, "nodePort": 30261 }, { "name": "admin", "protocol": "TCP", "port": 9990, "targetPort": 9990, "nodePort": 31850 } ], "selector": { "app": "l5d" }, "clusterIP": "10.199.242.33", "type": "LoadBalancer", "sessionAffinity": "None" }, "status": { "loadBalancer": { "ingress": [ { "ip": "104.155.170.94" } ] } } }, { "metadata": { "name": "world", "namespace": "pythonsky", "selfLink": "/api/v1/namespaces/pythonsky/services/world", "uid": "542bfddf-8c0a-11e6-a2a5-42010af00004", "resourceVersion": "11597434", "creationTimestamp": "2016-10-06T21:17:47Z", "annotations": { "kubectl.kubernetes.io/last-applied-configuration": "{\"kind\":\"Service\",\"apiVersion\":\"v1\",\"metadata\":{\"name\":\"world\",\"creationTimestamp\":null},\"spec\":{\"ports\":[{\"name\":\"http\",\"port\":7001,\"targetPort\":0}],\"selector\":{\"app\":\"world\"},\"clusterIP\":\"None\"},\"status\":{\"loadBalancer\":{}}}" } }, "spec": { "ports": [ { "name": "http", "protocol": "TCP", "port": 7001, "targetPort": 7001 } ], "selector": { "app": "world" }, "clusterIP": "None", "type": "ClusterIP", "sessionAffinity": "None" }, "status": { "loadBalancer": {} } }, { "metadata": { "name": "world-v2", "namespace": "pythonsky", "selfLink": "/api/v1/namespaces/pythonsky/services/world-v2", "uid": "545bef68-8c0a-11e6-a2a5-42010af00004", "resourceVersion": "11597443", "creationTimestamp": "2016-10-06T21:17:47Z", "annotations": { "kubectl.kubernetes.io/last-applied-configuration": "{\"kind\":\"Service\",\"apiVersion\":\"v1\",\"metadata\":{\"name\":\"world-v2\",\"creationTimestamp\":null},\"spec\":{\"ports\":[{\"name\":\"http\",\"port\":7001,\"targetPort\":0}],\"selector\":{\"app\":\"world-v2\"},\"clusterIP\":\"None\"},\"status\":{\"loadBalancer\":{}}}" } }, "spec": { "ports": [ { "name": "http", "protocol": "TCP", "port": 7001, "targetPort": 7001 } ], "selector": { "app": "world-v2" }, "clusterIP": "None", "type": "ClusterIP", "sessionAffinity": "None" }, "status": { "loadBalancer": {} } } ] }""")
    val Modified = Buf.Utf8("""{"type":"MODIFIED","object":{"kind":"Service","apiVersion":"v1","metadata":{"name":"l5d","namespace":"pythonsky","selfLink":"/api/v1/namespaces/pythonsky/services/l5d","uid":"53fb416f-8c0a-11e6-a2a5-42010af00004","resourceVersion":"11624505","creationTimestamp":"2016-10-06T21:17:46Z","annotations":{"kubectl.kubernetes.io/last-applied-configuration":"{\"kind\":\"Service\",\"apiVersion\":\"v1\",\"metadata\":{\"name\":\"l5d\",\"creationTimestamp\":null},\"spec\":{\"ports\":[{\"name\":\"external\",\"port\":80,\"targetPort\":8080},{\"name\":\"outgoing\",\"port\":4140,\"targetPort\":0},{\"name\":\"incoming\",\"port\":4141,\"targetPort\":0},{\"name\":\"admin\",\"port\":9990,\"targetPort\":0},{\"name\":\"admin2\",\"port\":9991,\"targetPort\":0}],\"selector\":{\"app\":\"l5d\"},\"type\":\"LoadBalancer\"},\"status\":{\"loadBalancer\":{}}}"}},"spec":{"ports":[{"name":"external","protocol":"TCP","port":80,"targetPort":8080,"nodePort":32131},{"name":"outgoing","protocol":"TCP","port":4140,"targetPort":4140,"nodePort":30735},{"name":"incoming","protocol":"TCP","port":4141,"targetPort":4141,"nodePort":30261},{"name":"admin","protocol":"TCP","port":9990,"targetPort":9990,"nodePort":31850},{"name":"admin2","protocol":"TCP","port":9991,"targetPort":9991,"nodePort":32561}],"selector":{"app":"l5d"},"clusterIP":"10.199.242.33","type":"LoadBalancer","sessionAffinity":"None"},"status":{"loadBalancer":{"ingress":[{"ip":"104.155.170.95"}]}}}}""")
    val Deleted = Buf.Utf8("""{"type":"DELETED","object":{"kind":"Service","apiVersion":"v1","metadata":{"name":"l5d","namespace":"pythonsky","selfLink":"/api/v1/namespaces/pythonsky/services/l5d","uid":"53fb416f-8c0a-11e6-a2a5-42010af00004","resourceVersion":"11624509","creationTimestamp":"2016-10-06T21:17:46Z","annotations":{"kubectl.kubernetes.io/last-applied-configuration":"{\"kind\":\"Service\",\"apiVersion\":\"v1\",\"metadata\":{\"name\":\"l5d\",\"creationTimestamp\":null},\"spec\":{\"ports\":[{\"name\":\"external\",\"port\":80,\"targetPort\":8080},{\"name\":\"outgoing\",\"port\":4140,\"targetPort\":0},{\"name\":\"incoming\",\"port\":4141,\"targetPort\":0},{\"name\":\"admin\",\"port\":9990,\"targetPort\":0},{\"name\":\"admin2\",\"port\":9991,\"targetPort\":0}],\"selector\":{\"app\":\"l5d\"},\"type\":\"LoadBalancer\"},\"status\":{\"loadBalancer\":{}}}"}},"spec":{"ports":[{"name":"external","protocol":"TCP","port":80,"targetPort":8080,"nodePort":32131},{"name":"outgoing","protocol":"TCP","port":4140,"targetPort":4140,"nodePort":30735},{"name":"incoming","protocol":"TCP","port":4141,"targetPort":4141,"nodePort":30261},{"name":"admin","protocol":"TCP","port":9990,"targetPort":9990,"nodePort":31850},{"name":"admin2","protocol":"TCP","port":9991,"targetPort":9991,"nodePort":32561}],"selector":{"app":"l5d"},"clusterIP":"10.199.242.33","type":"LoadBalancer","sessionAffinity":"None"},"status":{"loadBalancer":{"ingress":[{"ip":"104.155.170.95"}]}}}}""")
    val Created = Buf.Utf8("""{"type":"ADDED","object":{"kind":"Service","apiVersion":"v1","metadata":{"name":"foo","namespace":"pythonsky","selfLink":"/api/v1/namespaces/pythonsky/services/foo","uid":"53fb416f-8c0a-11e6-a2a5-42010af00004","resourceVersion":"11624506","creationTimestamp":"2016-10-06T21:17:46Z","annotations":{"kubectl.kubernetes.io/last-applied-configuration":"{\"kind\":\"Service\",\"apiVersion\":\"v1\",\"metadata\":{\"name\":\"foo\",\"creationTimestamp\":null},\"spec\":{\"ports\":[{\"name\":\"external\",\"port\":80,\"targetPort\":8080},{\"name\":\"outgoing\",\"port\":4140,\"targetPort\":0},{\"name\":\"incoming\",\"port\":4141,\"targetPort\":0},{\"name\":\"admin\",\"port\":9990,\"targetPort\":0},{\"name\":\"admin2\",\"port\":9991,\"targetPort\":0}],\"selector\":{\"app\":\"foo\"},\"type\":\"LoadBalancer\"},\"status\":{\"loadBalancer\":{}}}"}},"spec":{"ports":[{"name":"external","protocol":"TCP","port":80,"targetPort":8080,"nodePort":32131},{"name":"outgoing","protocol":"TCP","port":4140,"targetPort":4140,"nodePort":30735},{"name":"incoming","protocol":"TCP","port":4141,"targetPort":4141,"nodePort":30261},{"name":"admin","protocol":"TCP","port":9990,"targetPort":9990,"nodePort":31850},{"name":"admin2","protocol":"TCP","port":9991,"targetPort":9991,"nodePort":32561}],"selector":{"app":"foo"},"clusterIP":"10.199.242.33","type":"LoadBalancer","sessionAffinity":"None"},"status":{"loadBalancer":{"ingress":[{"ip":"104.155.170.95"}]}}}}""")
    val DeletePort = Buf.Utf8("""{"type":"MODIFIED","object":{"kind":"Service","apiVersion":"v1","metadata":{"name":"l5d","namespace":"pythonsky","selfLink":"/api/v1/namespaces/pythonsky/services/l5d","uid":"53fb416f-8c0a-11e6-a2a5-42010af00004","resourceVersion":"11624507","creationTimestamp":"2016-10-06T21:17:46Z","annotations":{"kubectl.kubernetes.io/last-applied-configuration":"{\"kind\":\"Service\",\"apiVersion\":\"v1\",\"metadata\":{\"name\":\"l5d\",\"creationTimestamp\":null},\"spec\":{\"ports\":[{\"name\":\"external\",\"port\":80,\"targetPort\":8080},{\"name\":\"outgoing\",\"port\":4140,\"targetPort\":0},{\"name\":\"incoming\",\"port\":4141,\"targetPort\":0},{\"name\":\"admin\",\"port\":9990,\"targetPort\":0}],\"selector\":{\"app\":\"l5d\"},\"type\":\"LoadBalancer\"},\"status\":{\"loadBalancer\":{}}}"}},"spec":{"ports":[{"name":"external","protocol":"TCP","port":80,"targetPort":8080,"nodePort":32131},{"name":"outgoing","protocol":"TCP","port":4140,"targetPort":4140,"nodePort":30735},{"name":"incoming","protocol":"TCP","port":4141,"targetPort":4141,"nodePort":30261},{"name":"admin","protocol":"TCP","port":9990,"targetPort":9990,"nodePort":31850}],"selector":{"app":"l5d"},"clusterIP":"10.199.242.33","type":"LoadBalancer","sessionAffinity":"None"},"status":{"loadBalancer":{"ingress":[{"ip":"104.155.170.95"}]}}}}""")
    val AddPort = Buf.Utf8("""{"type":"MODIFIED","object":{"kind":"Service","apiVersion":"v1","metadata":{"name":"l5d","namespace":"pythonsky","selfLink":"/api/v1/namespaces/pythonsky/services/l5d","uid":"53fb416f-8c0a-11e6-a2a5-42010af00004","resourceVersion":"11624508","creationTimestamp":"2016-10-06T21:17:46Z","annotations":{"kubectl.kubernetes.io/last-applied-configuration":"{\"kind\":\"Service\",\"apiVersion\":\"v1\",\"metadata\":{\"name\":\"l5d\",\"creationTimestamp\":null},\"spec\":{\"ports\":[{\"name\":\"external\",\"port\":80,\"targetPort\":8080},{\"name\":\"outgoing\",\"port\":4140,\"targetPort\":0},{\"name\":\"incoming\",\"port\":4141,\"targetPort\":0},{\"name\":\"admin\",\"port\":9990,\"targetPort\":0},{\"name\":\"admin2\",\"port\":9991,\"targetPort\":0}],\"selector\":{\"app\":\"l5d\"},\"type\":\"LoadBalancer\"},\"status\":{\"loadBalancer\":{}}}"}},"spec":{"ports":[{"name":"external","protocol":"TCP","port":80,"targetPort":8080,"nodePort":32131},{"name":"outgoing","protocol":"TCP","port":4140,"targetPort":4140,"nodePort":30735},{"name":"incoming","protocol":"TCP","port":4141,"targetPort":4141,"nodePort":30261},{"name":"admin","protocol":"TCP","port":9990,"targetPort":9990,"nodePort":31850},{"name":"admin2","protocol":"TCP","port":9991,"targetPort":9991,"nodePort":32561}],"selector":{"app":"l5d"},"clusterIP":"10.199.242.33","type":"LoadBalancer","sessionAffinity":"None"},"status":{"loadBalancer":{"ingress":[{"ip":"104.155.170.94"}]}}}}""")
  }

  trait Fixtures {

    @volatile var writer: Writer[Buf] = null

    val service = Service.mk[Request, Response] {
      case req if req.uri == "/api/v1/namespaces/pythonsky/services/l5d" =>
        val rsp = Response()
        rsp.content = Rsps.Init
        Future.value(rsp)

      case req if req.uri.startsWith("/api/v1/watch/namespaces/pythonsky/services/l5d") =>
        val rsp = Response()
        rsp.setChunked(true)

        writer = rsp.writer

        Future.value(rsp)

      case req if req.uri == "/api/v1/namespaces/pythonsky/services/foo" =>
        val rsp = Response()
        rsp.status = HttpStatus.NotFound
        rsp.content = Rsps.NotFound
        Future.value(rsp)

      case req if req.uri.startsWith("/api/v1/watch/namespaces/pythonsky/services/foo") =>
        val rsp = Response()
        rsp.setChunked(true)

        writer = rsp.writer

        Future.value(rsp)
      case req =>
        // As a workaround for an issue where some tests would enter an
        // infinite retry loop rather than failing, manually throw a
        // `TestFailedException` rather than calling `fail()`.
        //
        // `fail()` may provide slightly more useful information about
        // the failure location, but there was a concurrency issue where
        // the namer would keep retrying infinitely even after `fail()` was
        // called, causing SBT to hang. curiously, this issue doesn't seem
        // to apply when tests are run from IntelliJ?
        throw new TestFailedException(s"unexpected request: $req", 1)
    }
    val api = v1.Api(service)
    val timer = new MockTimer
    val namer = new ServiceNamer(Path.read("/test"), None, api.withNamespace, Stream.continually(1.millis))(timer)

    def lookup: Path

    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    val activity = namer.lookup(lookup)
    val _ = activity.states.respond { s =>
      state = s
    }
  }

  test("gets initial value and updates") {
    val _ = new Fixtures {
      def lookup = Path.read("/pythonsky/external/l5d/residual")

      var noChanges = false
      activity.values.respond { _ =>
        if (noChanges) fail("NameTree changed unexpectedly")
      }

      // initial value
      val init = await(activity.toFuture)
      val NameTree.Leaf(bound0: Name.Bound) = init
      val Addr.Bound(addresses0, meta0) = await(bound0.addr.changes.toFuture)
      assert(addresses0 == Set(Address(new InetSocketAddress("104.155.170.94", 80))))
      assert(bound0.id == Path.read("/test/pythonsky/external/l5d"))
      assert(bound0.path == Path.read("/residual"))

      // modified
      noChanges = true // modifying the address should not update the NameTree
      await(writer.write(Rsps.Modified))

      val modified = await(activity.toFuture)
      val NameTree.Leaf(bound1: Name.Bound) = init
      val Addr.Bound(addresses1, meta1) = await(bound1.addr.changes.toFuture)
      assert(addresses1 == Set(Address(new InetSocketAddress("104.155.170.95", 80))))
      assert(bound1.id == Path.read("/test/pythonsky/external/l5d"))
      assert(bound1.path == Path.read("/residual"))

      // deleted
      noChanges = false
      await(writer.write(Rsps.Deleted))

      val deleted = await(activity.toFuture)
      assert(deleted == NameTree.Neg)
    }
  }

  test("unknown service name is negative") {
    val _ = new Fixtures {
      def lookup = Path.read("/pythonsky/external/foo/residual")

      val init = await(activity.toFuture)
      assert(init == NameTree.Neg)
    }
  }

  test("unknown port name is negative") {
    val _ = new Fixtures {
      def lookup = Path.read("/pythonsky/foo/l5d/residual")

      val init = await(activity.toFuture)
      assert(init == NameTree.Neg)
    }
  }

  test("gets service creation") {
    val _ = new Fixtures {
      def lookup = Path.read("/pythonsky/external/foo/residual")

      val init = await(activity.toFuture)
      assert(init == NameTree.Neg)

      // created
      await(writer.write(Rsps.Created))

      val created = await(activity.toFuture)
      val NameTree.Leaf(bound: Name.Bound) = created
      val Addr.Bound(addresses, meta) = await(bound.addr.changes.toFuture)
      assert(addresses == Set(Address(new InetSocketAddress("104.155.170.95", 80))))
      assert(bound.id == Path.read("/test/pythonsky/external/foo"))
      assert(bound.path == Path.read("/residual"))
    }
  }

  test("gets port creation/deletion") {
    val _ = new Fixtures {
      def lookup = Path.read("/pythonsky/admin2/l5d/residual")

      val init = await(activity.toFuture)
      assert(init == NameTree.Neg)

      // modified (port created)
      await(writer.write(Rsps.Modified))

      val modified0 = await(activity.toFuture)
      val NameTree.Leaf(bound0: Name.Bound) = modified0
      val Addr.Bound(addresses0, meta0) = await(bound0.addr.changes.toFuture)
      assert(addresses0 == Set(Address(new InetSocketAddress("104.155.170.95", 9991))))
      assert(bound0.id == Path.read("/test/pythonsky/admin2/l5d"))
      assert(bound0.path == Path.read("/residual"))

      // modified (port deleted)
      await(writer.write(Rsps.DeletePort))

      val modified1 = await(activity.toFuture)
      assert(modified1 == NameTree.Neg)
    }
  }

  test("doesn't time travel") {
    val _ = new Fixtures {
      def lookup = Path.read("/pythonsky/admin2/l5d/residual")

      var noChanges = false
      activity.values.respond { _ =>
        if (noChanges) fail("NameTree changed unexpectedly")
      }

      val init = await(activity.toFuture)
      assert(init == NameTree.Neg)

      // modified (port created)
      await(writer.write(Rsps.Modified))

      val modified0 = await(activity.toFuture)
      val NameTree.Leaf(bound0: Name.Bound) = modified0
      val Addr.Bound(addresses0, meta0) = await(bound0.addr.changes.toFuture)
      bound0.addr.changes.respond { _ =>
        if (noChanges) fail("Addr changed unexpectedly")
      }

      // modified (port deleted)
      await(writer.write(Rsps.DeletePort))
      await(activity.toFuture)
      await(writer.write(Rsps.Deleted))
      await(activity.toFuture)

      noChanges = true

      // play events in reverse
      await(writer.write(Rsps.Created))
      await(activity.toFuture)

      await(writer.write(Rsps.Modified))
      await(activity.toFuture)
    }
  }

  test("updates to a different service should not cause NameTree updates") {
    val _ = new Fixtures {
      def lookup = Path.read("/pythonsky/external/l5d/residual")

      var noChanges = false
      activity.values.respond { _ =>
        if (noChanges) fail("NameTree changed unexpectedly")
      }

      // initial value
      val init = await(activity.toFuture)
      val NameTree.Leaf(bound: Name.Bound) = init
      val Addr.Bound(addresses, meta) = await(bound.addr.changes.toFuture)
      assert(addresses == Set(Address(new InetSocketAddress("104.155.170.94", 80))))
      assert(bound.id == Path.read("/test/pythonsky/external/l5d"))
      assert(bound.path == Path.read("/residual"))

      bound.addr.changes.respond { _ =>
        if (noChanges) fail("Addr changed unexpectedly")
      }

      // create foo service
      noChanges = true
      await(writer.write(Rsps.Created))
    }
  }

  test("is case insensitive") {
    val _ = new Fixtures {
      def lookup = Path.read("/pYtHoNsKy/ExTeRnAl/L5D/rEsIdUaL")

      // initial value
      val init = await(activity.toFuture)
      val NameTree.Leaf(bound0: Name.Bound) = init
      val Addr.Bound(addresses0, meta0) = await(bound0.addr.changes.toFuture)
      assert(addresses0 == Set(Address(new InetSocketAddress("104.155.170.94", 80))))
      assert(bound0.id == Path.read("/test/pYtHoNsKy/ExTeRnAl/L5D"))
      assert(bound0.path == Path.read("/rEsIdUaL"))
    }
  }
}
