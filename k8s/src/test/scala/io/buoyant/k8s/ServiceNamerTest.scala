package io.buoyant.k8s

import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.http.{Request, Response}
import com.twitter.io.{Writer, Buf}
import com.twitter.util._
import io.buoyant.namer.RichActivity
import io.buoyant.test.Awaits
import java.net.InetSocketAddress
import org.scalatest.FunSuite

class ServiceNamerTest extends FunSuite with Awaits {

  object Rsps {

    val Init = Buf.Utf8("""{ "kind": "ServiceList", "apiVersion": "v1", "metadata": { "selfLink": "/api/v1/namespaces/pythonsky/services", "resourceVersion": "11600623" }, "items": [ { "metadata": { "name": "hello", "namespace": "pythonsky", "selfLink": "/api/v1/namespaces/pythonsky/services/hello", "uid": "53b183a0-8c0a-11e6-a2a5-42010af00004", "resourceVersion": "11597405", "creationTimestamp": "2016-10-06T21:17:46Z", "annotations": { "kubectl.kubernetes.io/last-applied-configuration": "{\"kind\":\"Service\",\"apiVersion\":\"v1\",\"metadata\":{\"name\":\"hello\",\"creationTimestamp\":null},\"spec\":{\"ports\":[{\"name\":\"http\",\"port\":7000,\"targetPort\":0}],\"selector\":{\"app\":\"hello\"},\"clusterIP\":\"None\"},\"status\":{\"loadBalancer\":{}}}" } }, "spec": { "ports": [ { "name": "http", "protocol": "TCP", "port": 7000, "targetPort": 7000 } ], "selector": { "app": "hello" }, "clusterIP": "None", "type": "ClusterIP", "sessionAffinity": "None" }, "status": { "loadBalancer": {} } }, { "metadata": { "name": "l5d", "namespace": "pythonsky", "selfLink": "/api/v1/namespaces/pythonsky/services/l5d", "uid": "53fb416f-8c0a-11e6-a2a5-42010af00004", "resourceVersion": "11597494", "creationTimestamp": "2016-10-06T21:17:46Z", "annotations": { "kubectl.kubernetes.io/last-applied-configuration": "{\"kind\":\"Service\",\"apiVersion\":\"v1\",\"metadata\":{\"name\":\"l5d\",\"creationTimestamp\":null},\"spec\":{\"ports\":[{\"name\":\"external\",\"port\":80,\"targetPort\":8080},{\"name\":\"outgoing\",\"port\":4140,\"targetPort\":0},{\"name\":\"incoming\",\"port\":4141,\"targetPort\":0},{\"name\":\"admin\",\"port\":9990,\"targetPort\":0}],\"selector\":{\"app\":\"l5d\"},\"type\":\"LoadBalancer\"},\"status\":{\"loadBalancer\":{}}}" } }, "spec": { "ports": [ { "name": "external", "protocol": "TCP", "port": 80, "targetPort": 8080, "nodePort": 32131 }, { "name": "outgoing", "protocol": "TCP", "port": 4140, "targetPort": 4140, "nodePort": 30735 }, { "name": "incoming", "protocol": "TCP", "port": 4141, "targetPort": 4141, "nodePort": 30261 }, { "name": "admin", "protocol": "TCP", "port": 9990, "targetPort": 9990, "nodePort": 31850 } ], "selector": { "app": "l5d" }, "clusterIP": "10.199.242.33", "type": "LoadBalancer", "sessionAffinity": "None" }, "status": { "loadBalancer": { "ingress": [ { "ip": "104.155.170.94" } ] } } }, { "metadata": { "name": "world", "namespace": "pythonsky", "selfLink": "/api/v1/namespaces/pythonsky/services/world", "uid": "542bfddf-8c0a-11e6-a2a5-42010af00004", "resourceVersion": "11597434", "creationTimestamp": "2016-10-06T21:17:47Z", "annotations": { "kubectl.kubernetes.io/last-applied-configuration": "{\"kind\":\"Service\",\"apiVersion\":\"v1\",\"metadata\":{\"name\":\"world\",\"creationTimestamp\":null},\"spec\":{\"ports\":[{\"name\":\"http\",\"port\":7001,\"targetPort\":0}],\"selector\":{\"app\":\"world\"},\"clusterIP\":\"None\"},\"status\":{\"loadBalancer\":{}}}" } }, "spec": { "ports": [ { "name": "http", "protocol": "TCP", "port": 7001, "targetPort": 7001 } ], "selector": { "app": "world" }, "clusterIP": "None", "type": "ClusterIP", "sessionAffinity": "None" }, "status": { "loadBalancer": {} } }, { "metadata": { "name": "world-v2", "namespace": "pythonsky", "selfLink": "/api/v1/namespaces/pythonsky/services/world-v2", "uid": "545bef68-8c0a-11e6-a2a5-42010af00004", "resourceVersion": "11597443", "creationTimestamp": "2016-10-06T21:17:47Z", "annotations": { "kubectl.kubernetes.io/last-applied-configuration": "{\"kind\":\"Service\",\"apiVersion\":\"v1\",\"metadata\":{\"name\":\"world-v2\",\"creationTimestamp\":null},\"spec\":{\"ports\":[{\"name\":\"http\",\"port\":7001,\"targetPort\":0}],\"selector\":{\"app\":\"world-v2\"},\"clusterIP\":\"None\"},\"status\":{\"loadBalancer\":{}}}" } }, "spec": { "ports": [ { "name": "http", "protocol": "TCP", "port": 7001, "targetPort": 7001 } ], "selector": { "app": "world-v2" }, "clusterIP": "None", "type": "ClusterIP", "sessionAffinity": "None" }, "status": { "loadBalancer": {} } } ] }""")
    val Modified = Buf.Utf8("""{"type":"MODIFIED","object":{"kind":"Service","apiVersion":"v1","metadata":{"name":"l5d","namespace":"pythonsky","selfLink":"/api/v1/namespaces/pythonsky/services/l5d","uid":"53fb416f-8c0a-11e6-a2a5-42010af00004","resourceVersion":"11624505","creationTimestamp":"2016-10-06T21:17:46Z","annotations":{"kubectl.kubernetes.io/last-applied-configuration":"{\"kind\":\"Service\",\"apiVersion\":\"v1\",\"metadata\":{\"name\":\"l5d\",\"creationTimestamp\":null},\"spec\":{\"ports\":[{\"name\":\"external\",\"port\":80,\"targetPort\":8080},{\"name\":\"outgoing\",\"port\":4140,\"targetPort\":0},{\"name\":\"incoming\",\"port\":4141,\"targetPort\":0},{\"name\":\"admin\",\"port\":9990,\"targetPort\":0},{\"name\":\"admin2\",\"port\":9991,\"targetPort\":0}],\"selector\":{\"app\":\"l5d\"},\"type\":\"LoadBalancer\"},\"status\":{\"loadBalancer\":{}}}"}},"spec":{"ports":[{"name":"external","protocol":"TCP","port":80,"targetPort":8080,"nodePort":32131},{"name":"outgoing","protocol":"TCP","port":4140,"targetPort":4140,"nodePort":30735},{"name":"incoming","protocol":"TCP","port":4141,"targetPort":4141,"nodePort":30261},{"name":"admin","protocol":"TCP","port":9990,"targetPort":9990,"nodePort":31850},{"name":"admin2","protocol":"TCP","port":9991,"targetPort":9991,"nodePort":32561}],"selector":{"app":"l5d"},"clusterIP":"10.199.242.33","type":"LoadBalancer","sessionAffinity":"None"},"status":{"loadBalancer":{"ingress":[{"ip":"104.155.170.95"}]}}}}""")
    val Deleted = Buf.Utf8("""{"type":"DELETED","object":{"kind":"Service","apiVersion":"v1","metadata":{"name":"l5d","namespace":"pythonsky","selfLink":"/api/v1/namespaces/pythonsky/services/l5d","uid":"53fb416f-8c0a-11e6-a2a5-42010af00004","resourceVersion":"11624505","creationTimestamp":"2016-10-06T21:17:46Z","annotations":{"kubectl.kubernetes.io/last-applied-configuration":"{\"kind\":\"Service\",\"apiVersion\":\"v1\",\"metadata\":{\"name\":\"l5d\",\"creationTimestamp\":null},\"spec\":{\"ports\":[{\"name\":\"external\",\"port\":80,\"targetPort\":8080},{\"name\":\"outgoing\",\"port\":4140,\"targetPort\":0},{\"name\":\"incoming\",\"port\":4141,\"targetPort\":0},{\"name\":\"admin\",\"port\":9990,\"targetPort\":0},{\"name\":\"admin2\",\"port\":9991,\"targetPort\":0}],\"selector\":{\"app\":\"l5d\"},\"type\":\"LoadBalancer\"},\"status\":{\"loadBalancer\":{}}}"}},"spec":{"ports":[{"name":"external","protocol":"TCP","port":80,"targetPort":8080,"nodePort":32131},{"name":"outgoing","protocol":"TCP","port":4140,"targetPort":4140,"nodePort":30735},{"name":"incoming","protocol":"TCP","port":4141,"targetPort":4141,"nodePort":30261},{"name":"admin","protocol":"TCP","port":9990,"targetPort":9990,"nodePort":31850},{"name":"admin2","protocol":"TCP","port":9991,"targetPort":9991,"nodePort":32561}],"selector":{"app":"l5d"},"clusterIP":"10.199.242.33","type":"LoadBalancer","sessionAffinity":"None"},"status":{"loadBalancer":{"ingress":[{"ip":"104.155.170.95"}]}}}}""")
    val Created = Buf.Utf8("""{"type":"ADDED","object":{"kind":"Service","apiVersion":"v1","metadata":{"name":"foo","namespace":"pythonsky","selfLink":"/api/v1/namespaces/pythonsky/services/foo","uid":"53fb416f-8c0a-11e6-a2a5-42010af00004","resourceVersion":"11624505","creationTimestamp":"2016-10-06T21:17:46Z","annotations":{"kubectl.kubernetes.io/last-applied-configuration":"{\"kind\":\"Service\",\"apiVersion\":\"v1\",\"metadata\":{\"name\":\"foo\",\"creationTimestamp\":null},\"spec\":{\"ports\":[{\"name\":\"external\",\"port\":80,\"targetPort\":8080},{\"name\":\"outgoing\",\"port\":4140,\"targetPort\":0},{\"name\":\"incoming\",\"port\":4141,\"targetPort\":0},{\"name\":\"admin\",\"port\":9990,\"targetPort\":0},{\"name\":\"admin2\",\"port\":9991,\"targetPort\":0}],\"selector\":{\"app\":\"foo\"},\"type\":\"LoadBalancer\"},\"status\":{\"loadBalancer\":{}}}"}},"spec":{"ports":[{"name":"external","protocol":"TCP","port":80,"targetPort":8080,"nodePort":32131},{"name":"outgoing","protocol":"TCP","port":4140,"targetPort":4140,"nodePort":30735},{"name":"incoming","protocol":"TCP","port":4141,"targetPort":4141,"nodePort":30261},{"name":"admin","protocol":"TCP","port":9990,"targetPort":9990,"nodePort":31850},{"name":"admin2","protocol":"TCP","port":9991,"targetPort":9991,"nodePort":32561}],"selector":{"app":"foo"},"clusterIP":"10.199.242.33","type":"LoadBalancer","sessionAffinity":"None"},"status":{"loadBalancer":{"ingress":[{"ip":"104.155.170.95"}]}}}}""")
    val DeletePort = Buf.Utf8("""{"type":"MODIFIED","object":{"kind":"Service","apiVersion":"v1","metadata":{"name":"l5d","namespace":"pythonsky","selfLink":"/api/v1/namespaces/pythonsky/services/l5d","uid":"53fb416f-8c0a-11e6-a2a5-42010af00004","resourceVersion":"11624505","creationTimestamp":"2016-10-06T21:17:46Z","annotations":{"kubectl.kubernetes.io/last-applied-configuration":"{\"kind\":\"Service\",\"apiVersion\":\"v1\",\"metadata\":{\"name\":\"l5d\",\"creationTimestamp\":null},\"spec\":{\"ports\":[{\"name\":\"external\",\"port\":80,\"targetPort\":8080},{\"name\":\"outgoing\",\"port\":4140,\"targetPort\":0},{\"name\":\"incoming\",\"port\":4141,\"targetPort\":0},{\"name\":\"admin\",\"port\":9990,\"targetPort\":0}],\"selector\":{\"app\":\"l5d\"},\"type\":\"LoadBalancer\"},\"status\":{\"loadBalancer\":{}}}"}},"spec":{"ports":[{"name":"external","protocol":"TCP","port":80,"targetPort":8080,"nodePort":32131},{"name":"outgoing","protocol":"TCP","port":4140,"targetPort":4140,"nodePort":30735},{"name":"incoming","protocol":"TCP","port":4141,"targetPort":4141,"nodePort":30261},{"name":"admin","protocol":"TCP","port":9990,"targetPort":9990,"nodePort":31850}],"selector":{"app":"l5d"},"clusterIP":"10.199.242.33","type":"LoadBalancer","sessionAffinity":"None"},"status":{"loadBalancer":{"ingress":[{"ip":"104.155.170.95"}]}}}}""")
    val AddPort = Buf.Utf8("""{"type":"MODIFIED","object":{"kind":"Service","apiVersion":"v1","metadata":{"name":"l5d","namespace":"pythonsky","selfLink":"/api/v1/namespaces/pythonsky/services/l5d","uid":"53fb416f-8c0a-11e6-a2a5-42010af00004","resourceVersion":"11624505","creationTimestamp":"2016-10-06T21:17:46Z","annotations":{"kubectl.kubernetes.io/last-applied-configuration":"{\"kind\":\"Service\",\"apiVersion\":\"v1\",\"metadata\":{\"name\":\"l5d\",\"creationTimestamp\":null},\"spec\":{\"ports\":[{\"name\":\"external\",\"port\":80,\"targetPort\":8080},{\"name\":\"outgoing\",\"port\":4140,\"targetPort\":0},{\"name\":\"incoming\",\"port\":4141,\"targetPort\":0},{\"name\":\"admin\",\"port\":9990,\"targetPort\":0},{\"name\":\"admin2\",\"port\":9991,\"targetPort\":0}],\"selector\":{\"app\":\"l5d\"},\"type\":\"LoadBalancer\"},\"status\":{\"loadBalancer\":{}}}"}},"spec":{"ports":[{"name":"external","protocol":"TCP","port":80,"targetPort":8080,"nodePort":32131},{"name":"outgoing","protocol":"TCP","port":4140,"targetPort":4140,"nodePort":30735},{"name":"incoming","protocol":"TCP","port":4141,"targetPort":4141,"nodePort":30261},{"name":"admin","protocol":"TCP","port":9990,"targetPort":9990,"nodePort":31850},{"name":"admin2","protocol":"TCP","port":9991,"targetPort":9991,"nodePort":32561}],"selector":{"app":"l5d"},"clusterIP":"10.199.242.33","type":"LoadBalancer","sessionAffinity":"None"},"status":{"loadBalancer":{"ingress":[{"ip":"104.155.170.94"}]}}}}""")
  }

  trait Fixtures {

    @volatile var writer: Writer = null

    val service = Service.mk[Request, Response] {
      case req if req.uri == "/api/v1/namespaces/pythonsky/services" =>
        val rsp = Response()
        rsp.content = Rsps.Init
        Future.value(rsp)

      case req if req.uri == "/api/v1/namespaces/pythonsky/services?watch=true&resourceVersion=11600623" =>
        val rsp = Response()
        rsp.setChunked(true)

        writer = rsp.writer

        Future.value(rsp)
      case req =>
        fail(s"unexpected request: $req")
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
