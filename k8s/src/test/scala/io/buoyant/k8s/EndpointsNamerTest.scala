package io.buoyant.k8s

import com.twitter.conversions.time._
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.{Addr, Name, NameTree, Path, Service}
import com.twitter.io.Buf
import com.twitter.util._
import io.buoyant.test.Awaits
import org.scalatest.FunSuite
import org.scalatest.exceptions.TestFailedException

class EndpointsNamerTest extends FunSuite with Awaits {

  object Rsps {

    val Init = Buf.Utf8("""{"kind":"EndpointsList","apiVersion":"v1","metadata":{"selfLink":"/api/v1/namespaces/srv/endpoints","resourceVersion":"5319481"},"items":[{"metadata":{"name":"accounts","namespace":"srv","selfLink":"/api/v1/namespaces/srv/endpoints/accounts","uid":"6b4f8411-525e-11e5-9859-42010af01815","resourceVersion":"4969550","creationTimestamp":"2015-09-03T17:08:38Z"},"subsets":[{"addresses":[{"ip":"10.248.2.10","targetRef":{"kind":"Pod","namespace":"srv","name":"accounts-wl3n0","uid":"6ada6391-525e-11e5-9859-42010af01815","resourceVersion":"4962571"}},{"ip":"10.248.4.10","targetRef":{"kind":"Pod","namespace":"srv","name":"accounts-bbukv","uid":"6ada5545-525e-11e5-9859-42010af01815","resourceVersion":"4962603"}},{"ip":"10.248.5.10","targetRef":{"kind":"Pod","namespace":"srv","name":"accounts-3zbq3","uid":"6ada42a6-525e-11e5-9859-42010af01815","resourceVersion":"4962601"}}],"ports":[{"name":"http","port":8086,"protocol":"TCP"}]}]},{"metadata":{"name":"auth","namespace":"srv","selfLink":"/api/v1/namespaces/srv/endpoints/auth","uid":"6982a2fc-525e-11e5-9859-42010af01815","resourceVersion":"4969546","creationTimestamp":"2015-09-03T17:08:35Z"},"subsets":[{"addresses":[{"ip":"10.248.0.10","targetRef":{"kind":"Pod","namespace":"srv","name":"auth-vmcia","uid":"690ef84c-525e-11e5-9859-42010af01815","resourceVersion":"4962519"}},{"ip":"10.248.1.9","targetRef":{"kind":"Pod","namespace":"srv","name":"auth-d8qnl","uid":"690f06c8-525e-11e5-9859-42010af01815","resourceVersion":"4962570"}},{"ip":"10.248.5.9","targetRef":{"kind":"Pod","namespace":"srv","name":"auth-m79ya","uid":"690f05df-525e-11e5-9859-42010af01815","resourceVersion":"4962464"}}],"ports":[{"name":"http","port":8082,"protocol":"TCP"}]}]},{"metadata":{"name":"events","namespace":"srv","selfLink":"/api/v1/namespaces/srv/endpoints/events","uid":"67abfc86-525e-11e5-9859-42010af01815","resourceVersion":"4962380","creationTimestamp":"2015-09-03T17:08:32Z"},"subsets":[{"addresses":[{"ip":"10.248.0.9","targetRef":{"kind":"Pod","namespace":"srv","name":"events-6g3in","uid":"673a6ebf-525e-11e5-9859-42010af01815","resourceVersion":"4962378"}},{"ip":"10.248.5.8","targetRef":{"kind":"Pod","namespace":"srv","name":"events-l8xyq","uid":"673a68fe-525e-11e5-9859-42010af01815","resourceVersion":"4962374"}},{"ip":"10.248.6.8","targetRef":{"kind":"Pod","namespace":"srv","name":"events-4hkt8","uid":"673a664a-525e-11e5-9859-42010af01815","resourceVersion":"4962350"}}],"ports":[{"name":"http","port":8085,"protocol":"TCP"}]}]},{"metadata":{"name":"projects","namespace":"srv","selfLink":"/api/v1/namespaces/srv/endpoints/projects","uid":"6c39393c-525e-11e5-9859-42010af01815","resourceVersion":"4962611","creationTimestamp":"2015-09-03T17:08:40Z"},"subsets":[{"addresses":[{"ip":"10.248.0.11","targetRef":{"kind":"Pod","namespace":"srv","name":"projects-h2zbp","uid":"6bc6a899-525e-11e5-9859-42010af01815","resourceVersion":"4962606"}},{"ip":"10.248.7.12","targetRef":{"kind":"Pod","namespace":"srv","name":"projects-fzfv2","uid":"6bc6b7be-525e-11e5-9859-42010af01815","resourceVersion":"4962607"}},{"ip":"10.248.8.10","targetRef":{"kind":"Pod","namespace":"srv","name":"projects-0o69j","uid":"6bc6c27c-525e-11e5-9859-42010af01815","resourceVersion":"4962610"}}],"ports":[{"name":"http","port":8087,"protocol":"TCP"}]}]},{"metadata":{"name":"sessions","namespace":"srv","selfLink":"/api/v1/namespaces/srv/endpoints/sessions","uid":"6a698096-525e-11e5-9859-42010af01815","resourceVersion":"4962526","creationTimestamp":"2015-09-03T17:08:37Z"},"subsets":[{"addresses":[{"ip":"10.248.4.9","targetRef":{"kind":"Pod","namespace":"srv","name":"sessions-293kc","uid":"69f5a7d2-525e-11e5-9859-42010af01815","resourceVersion":"4962471"}},{"ip":"10.248.7.11","targetRef":{"kind":"Pod","namespace":"srv","name":"sessions-mr9gb","uid":"69f5b78e-525e-11e5-9859-42010af01815","resourceVersion":"4962524"}},{"ip":"10.248.8.9","targetRef":{"kind":"Pod","namespace":"srv","name":"sessions-nicom","uid":"69f5b623-525e-11e5-9859-42010af01815","resourceVersion":"4962517"}}],"ports":[{"name":"http","port":8083,"protocol":"TCP"}]}]},{"metadata":{"name":"users","namespace":"srv","selfLink":"/api/v1/namespaces/srv/endpoints/users","uid":"689a2ff3-525e-11e5-9859-42010af01815","resourceVersion":"4962565","creationTimestamp":"2015-09-03T17:08:34Z"},"subsets":[{"addresses":[{"ip":"10.248.2.9","targetRef":{"kind":"Pod","namespace":"srv","name":"users-o8vvr","uid":"681d409e-525e-11e5-9859-42010af01815","resourceVersion":"4962462"}},{"ip":"10.248.4.8","targetRef":{"kind":"Pod","namespace":"srv","name":"users-u3lnj","uid":"681d31f2-525e-11e5-9859-42010af01815","resourceVersion":"4962422"}},{"ip":"10.248.6.9","targetRef":{"kind":"Pod","namespace":"srv","name":"users-m3fmq","uid":"681d3fa6-525e-11e5-9859-42010af01815","resourceVersion":"4962564"}}],"ports":[{"name":"http","port":8081,"protocol":"TCP"}]}]},{"metadata":{"name":"web","namespace":"srv","selfLink":"/api/v1/namespaces/srv/endpoints/web","uid":"6d5bd683-525e-11e5-9859-42010af01815","resourceVersion":"4962619","creationTimestamp":"2015-09-03T17:08:41Z"},"subsets":[{"addresses":[{"ip":"10.248.1.10","targetRef":{"kind":"Pod","namespace":"srv","name":"web-9n3g9","uid":"6ceacdf3-525e-11e5-9859-42010af01815","resourceVersion":"4962617"}},{"ip":"10.248.2.11","targetRef":{"kind":"Pod","namespace":"srv","name":"web-2o0ut","uid":"6ceacd0d-525e-11e5-9859-42010af01815","resourceVersion":"4962616"}},{"ip":"10.248.6.10","targetRef":{"kind":"Pod","namespace":"srv","name":"web-hxz7q","uid":"6ceabb69-525e-11e5-9859-42010af01815","resourceVersion":"4962614"}}],"ports":[{"name":"http","port":8084,"protocol":"TCP"}]}]}]}""")

    val ScaleUp = Buf.Utf8("""{"type":"MODIFIED","object":{"kind":"Endpoints","apiVersion":"v1","metadata":{"name":"sessions","namespace":"srv","selfLink":"/api/v1/namespaces/srv/endpoints/sessions","uid":"6a698096-525e-11e5-9859-42010af01815","resourceVersion":"5319582","creationTimestamp":"2015-09-03T17:08:37Z"},"subsets":[{"addresses":[{"ip":"10.248.1.11","targetRef":{"kind":"Pod","namespace":"srv","name":"sessions-09ujq","uid":"669b7a4f-55ef-11e5-a801-42010af08a01","resourceVersion":"5319581"}},{"ip":"10.248.4.9","targetRef":{"kind":"Pod","namespace":"srv","name":"sessions-293kc","uid":"69f5a7d2-525e-11e5-9859-42010af01815","resourceVersion":"4962471"}},{"ip":"10.248.7.11","targetRef":{"kind":"Pod","namespace":"srv","name":"sessions-mr9gb","uid":"69f5b78e-525e-11e5-9859-42010af01815","resourceVersion":"4962524"}},{"ip":"10.248.8.9","targetRef":{"kind":"Pod","namespace":"srv","name":"sessions-nicom","uid":"69f5b623-525e-11e5-9859-42010af01815","resourceVersion":"4962517"}}],"ports":[{"name":"http","port":8083,"protocol":"TCP"}]}]}}""")

    val ScaleDown = Buf.Utf8("""{"type":"MODIFIED","object":{"kind":"Endpoints","apiVersion":"v1","metadata":{"name":"sessions","namespace":"srv","selfLink":"/api/v1/namespaces/srv/endpoints/sessions","uid":"6a698096-525e-11e5-9859-42010af01815","resourceVersion":"5319605","creationTimestamp":"2015-09-03T17:08:37Z"},"subsets":[{"addresses":[{"ip":"10.248.4.9","targetRef":{"kind":"Pod","namespace":"srv","name":"sessions-293kc","uid":"69f5a7d2-525e-11e5-9859-42010af01815","resourceVersion":"4962471"}},{"ip":"10.248.7.11","targetRef":{"kind":"Pod","namespace":"srv","name":"sessions-mr9gb","uid":"69f5b78e-525e-11e5-9859-42010af01815","resourceVersion":"4962524"}},{"ip":"10.248.8.9","targetRef":{"kind":"Pod","namespace":"srv","name":"sessions-nicom","uid":"69f5b623-525e-11e5-9859-42010af01815","resourceVersion":"4962517"}}],"ports":[{"name":"http","port":8083,"protocol":"TCP"}]}]}}""")
  }

  trait Fixtures {
    @volatile var doInit, didInit, doScaleUp, doScaleDown = new Promise[Unit]

    val service = Service.mk[Request, Response] {
      case req if req.uri == "/api/v1/namespaces/srv/endpoints" =>
        val rsp = Response()
        rsp.content = Rsps.Init
        doInit before Future.value(rsp)

      case req if req.uri == "/api/v1/namespaces/srv/endpoints?watch=true&resourceVersion=5319481" =>
        val rsp = Response()

        doScaleUp before rsp.writer.write(Rsps.ScaleUp) before {
          doScaleDown before rsp.writer.write(Rsps.ScaleDown)
        }

        Future.value(rsp)

      case req =>
        fail(s"unexpected request: $req")
    }
    val api = v1.Api(service)
    val timer = new MockTimer
    val namer = new EndpointsNamer(Path.read("/test"), api.withNamespace, Stream.continually(1.millis))(timer)

    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    val _ = namer.lookup(Path.read("/srv/http/sessions")).states.respond { s =>
      state = s
    }
    def assertHas(n: Int): Unit =
      state match {
        case Activity.Ok(NameTree.Leaf(bound: Name.Bound)) =>
          bound.addr.sample() match {
            case Addr.Bound(addrs, _) =>
              assert(addrs.size == n)
            case addr =>
              throw new TestFailedException(s"expected bound addr, got $addr", 1)
          }
        case v =>
          throw new TestFailedException(s"unexpected state: $v", 1)
      }
  }

  test("watches a namespace and receives updates") {
    val _ = new Fixtures {
      assert(state == Activity.Pending)
      doInit.setDone()
      assertHas(3)

      doScaleUp.setDone()
      assertHas(4)

      doScaleDown.setDone()
      assertHas(3)
    }
  }

  test("retries initial failures") {
    Time.withCurrentTimeFrozen { time =>
      val _ = new Fixtures {
        val e = new Exception()
        assert(state == Activity.Pending)
        doInit.setException(e)

        time.advance(1.millis)
        timer.tick()
        assert(state == Activity.Pending)

        doInit = new Promise[Unit]
        doInit.setDone()
        time.advance(1.millis)
        timer.tick()
        assertHas(3)
      }
    }
  }

  test("missing port names are negative") {
    val service = Service.mk[Request, Response] {
      case req if req.uri == "/api/v1/namespaces/srv/endpoints" =>
        val rsp = Response()
        rsp.content = Rsps.Init
        Future.value(rsp)

      case req if req.uri == "/api/v1/namespaces/srv/endpoints?watch=true&resourceVersion=5319481" =>
        Future.value(Response())

      case req =>
        fail(s"unexpected request: $req")
    }
    val api = v1.Api(service)
    val namer = new EndpointsNamer(Path.read("/test"), api.withNamespace)

    @volatile var state: Activity.State[NameTree[Name]] = Activity.Pending
    val _ = namer.lookup(Path.read("/srv/thrift/sessions")).states respond { s =>
      state = s
    }

    assert(state == Activity.Ok(NameTree.Neg))
  }
}
