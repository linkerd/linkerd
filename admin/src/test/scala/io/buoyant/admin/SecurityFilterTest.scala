package io.buoyant.admin

import com.twitter.finagle.Service
import com.twitter.finagle.http._
import com.twitter.util.{Await, Future}
import org.scalatest.prop.TableDrivenPropertyChecks._
import org.scalatest.{FlatSpec, Matchers, Status => _}

class SecurityFilterTest extends FlatSpec with Matchers {

  private[this] val ok = Service.mk[Request, Response] { request => Future.value(Response(request)) }

  it should "let pass ui requests" in {

    val service = SecurityFilter(
      controlEnabled = false,
      diagnosticsEnabled = false
    ) andThen ok

    val params = Table(
      ("uri", "response status"),
      ("/?router=out", Status.Ok),
      ("/files/css/lib/bootstrap.min.css", Status.Ok),
      ("/files/css/lib/fonts.css", Status.Ok),
      ("/files/images/linkerd-horizontal-white-transbg-vectorized.svg", Status.Ok),
      ("/files/js/lib/require.js", Status.Ok),
      ("/files/css/fonts/SourceSansPro-300.woff2", Status.Ok),
      ("/config.json", Status.Ok),
      ("/metrics.json", Status.Ok),
      ("/delegator?router=out", Status.Ok),
      ("/logging", Status.Ok),
      ("/help", Status.Ok),

      ("/admin/shutdown", Status.NotFound),
      ("/admin/threads", Status.NotFound)
    )

    forAll(params) { (uri, status) =>
      val req = Request(Version.Http11, Method.Get, uri)
      val rep = Await.result(service(req))
      assert(rep.status == status)
    }
  }

  it should "let pass configured requests" in {

    val service = SecurityFilter(
      controlEnabled = false,
      diagnosticsEnabled = false,
      whitelist = Seq(
        "^/fooba[rz]$".r,
        "^/abc/def/.*$".r
      )
    ) andThen ok

    val params = Table(
      ("uri", "response status"),
      ("/?router=out", Status.Ok),
      ("/files/css/lib/bootstrap.min.css", Status.Ok),
      ("/config.json", Status.Ok),
      ("/foobar", Status.Ok),
      ("/foobaz", Status.Ok),
      ("/foobaf", Status.NotFound),
      ("/abc/def", Status.NotFound),
      ("/abc/def/ghi", Status.Ok),
      ("/admin/shutdown", Status.NotFound),
      ("/admin/threads", Status.NotFound)
    )

    forAll(params) { (uri, status) =>
      val req = Request(Version.Http11, Method.Get, uri)
      val rep = Await.result(service(req))
      assert(rep.status == status)
    }
  }

  it should "not let pass ui requests if not permitted" in {
    val service = SecurityFilter(
      uiEnabled = false,
      controlEnabled = false,
      diagnosticsEnabled = false,
      whitelist = Seq(
        "^/fooba[rz]$".r,
        "^/abc/def/.*$".r
      )
    ) andThen ok

    val params = Table(
      ("uri", "response status"),
      ("/?router=out", Status.NotFound),
      ("/files/css/lib/bootstrap.min.css", Status.NotFound),
      ("/foobar", Status.Ok),
      ("/foobaz", Status.Ok),
      ("/foobaf", Status.NotFound),
      ("/abc/def", Status.NotFound),
      ("/abc/def/ghi", Status.Ok),
      ("/admin/shutdown", Status.NotFound),
      ("/admin/threads", Status.NotFound)
    )

    forAll(params) { (uri, status) =>
      val req = Request(Version.Http11, Method.Get, uri)
      val rep = Await.result(service(req))
      assert(rep.status == status)
    }
  }

  it should "let pass control requests if permitted" in {
    val service = SecurityFilter(
      diagnosticsEnabled = false
    ) andThen ok

    val params = Table(
      ("uri", "response status"),
      ("/admin/shutdown", Status.Ok),
      ("/logging.json", Status.Ok),
      ("/sth/else", Status.NotFound)
    )

    forAll(params) { (uri, status) =>
      val req = Request(Version.Http11, Method.Get, uri)
      val rep = Await.result(service(req))
      assert(rep.status == status)
    }
  }

  it should "block blacklisted URLs" in {
    val service = SecurityFilter(
      blacklist = Seq("^/help$".r)
    ) andThen ok

    val params = Table(
      ("uri", "response status"),
      ("/files/css/lib/bootstrap.min.css", Status.Ok),
      ("/files/css/lib/fonts.css", Status.Ok),
      ("/help", Status.NotFound)
    )

    forAll(params) { (uri, status) =>
      val req = Request(Version.Http11, Method.Get, uri)
      val rep = Await.result(service(req))
      assert(rep.status == status)
    }
  }

  it should "blacklist overrides whitelist" in {
    val service = SecurityFilter(
      whitelist = Seq("^/help$".r),
      blacklist = Seq("^/help$".r)
    ) andThen ok

    val params = Table(
      ("uri", "response status"),
      ("/files/css/lib/bootstrap.min.css", Status.Ok),
      ("/help", Status.NotFound)
    )

    forAll(params) { (uri, status) =>
      val req = Request(Version.Http11, Method.Get, uri)
      val rep = Await.result(service(req))
      assert(rep.status == status)
    }
  }
}
