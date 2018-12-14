package com.twitter.finagle.buoyant.linkerd

import com.twitter.conversions.DurationOps._
import com.twitter.finagle.{Addr, Dtab, Path, Service, ServiceFactory, Stack}
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.context.{Contexts, Deadline}
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.tracing.Trace
import com.twitter.util.{Future, Time, Var}
import io.buoyant.test.FunSuite

class HeadersTest extends FunSuite {

  test("round trip encoding for ctx header") {
    val orig = Trace.nextId
    val headers = Request().headerMap

    assert(!headers.contains(Headers.Ctx.Trace.Key))
    Headers.Ctx.Trace.set(headers, orig)
    assert(headers.contains(Headers.Ctx.Trace.Key))
    assert(Headers.Ctx.Trace.get(headers) == Some(orig))

    Headers.Ctx.Trace.clear(headers)
    assert(!headers.contains(Headers.Ctx.Trace.Key))
  }

  test("round trip encoding for request id header") {
    val orig = Trace.nextId
    val headers = Request().headerMap

    assert(!headers.contains(Headers.RequestId.Key))
    Headers.RequestId.set(headers, orig)
    assert(headers.contains(Headers.RequestId.Key))
    assert(headers.get(Headers.RequestId.Key) == Some(orig.traceId.toString))
  }

  test("only extract valid sample header values") {
    val headers = Request().headerMap
    assert(Headers.Sample.get(headers).isEmpty)

    headers(Headers.Sample.Key) = "yes"
    assert(Headers.Sample.get(headers).isEmpty)

    headers(Headers.Sample.Key) = "-3"
    assert(Headers.Sample.get(headers).contains(0f))

    headers(Headers.Sample.Key) = "0"
    assert(Headers.Sample.get(headers).contains(0f))

    headers(Headers.Sample.Key) = "0.2"
    assert(Headers.Sample.get(headers).contains(0.2f))

    headers(Headers.Sample.Key) = "1"
    assert(Headers.Sample.get(headers).contains(1f))

    headers(Headers.Sample.Key) = "1.5"
    assert(Headers.Sample.get(headers).contains(1f))
  }

  test("Headers.Ctx.serverModule sets Dtab.local") {
    @volatile var dtab: Option[Dtab] = None
    val svc = {
      val sf = ServiceFactory.const(Service.mk[Request, Response] { req =>
        dtab = Some(Dtab.local)
        Future.value(Response())
      })
      val stk = Headers.Ctx.serverModule +: Stack.leaf(Stack.Role("sf"), sf)
      await(stk.make(Stack.Params.empty)())
    }

    val req = Request()
    req.headerMap("l5d-dtab") = "/a => /d"
    req.headerMap("l5d-ctx-dtab") = "/a => /c"
    Dtab.unwind {
      Dtab.local = Dtab.read("/a => /b")
      val _ = await(svc(req))
    }
    assert(!req.headerMap.contains(Headers.Ctx.Dtab.CtxKey))
    assert(!req.headerMap.contains(Headers.Ctx.Dtab.UserKey))
    assert(dtab == Some(Dtab.read("/a=>/b;/a=>/c;/a=>/d")))
  }

  test("Headers.Ctx.serverModule errors on invalid dtab") {
    @volatile var dtab: Option[Dtab] = None
    val svc = {
      val sf = ServiceFactory.const(Service.mk[Request, Response] { req =>
        dtab = Some(Dtab.local)
        Future.value(Response())
      })
      val stk = Headers.Ctx.serverModule +: Stack.leaf(Stack.Role("sf"), sf)
      await(stk.make(Stack.Params.empty)())
    }

    val req = Request()
    req.headerMap("l5d-dtab") = "i am not a dtab"
    val rsp = await(svc(req))
    assert(rsp.statusCode == 400)
  }

  test("Headers.Ctx.serverModule sets Deadline") {
    @volatile var deadline: Option[Deadline] = None
    val svc = {
      val sf = ServiceFactory.const(Service.mk[Request, Response] { req =>
        deadline = Deadline.current
        Future.value(Response())
      })
      val stk = Headers.Ctx.serverModule +: Stack.leaf(Stack.Role("sf"), sf)
      await(stk.make(Stack.Params.empty)())
    }

    val req = Request()
    val dl = Deadline(Time.now, Time.now + 5.seconds)
    Headers.Ctx.Deadline.set(req.headerMap, dl)
    assert(req.headerMap.contains(Headers.Ctx.Deadline.Key))
    val _ = await(svc(req))
    assert(!req.headerMap.contains(Headers.Ctx.Deadline.Key))
    assert(deadline == Some(dl))
  }

  test("Headers.Ctx.Dtab.ClientFilter sets Dtab") {
    @volatile var dtab: Option[String] = None
    val svc = new Headers.Ctx.Dtab.ClientFilter andThen Service.mk[Request, Response] { req =>
      dtab = req.headerMap.get(Headers.Ctx.Dtab.CtxKey)
      Future.value(Response())
    }

    Dtab.unwind {
      Dtab.local = Dtab.read("/foo=>/bar")
      val _ = await(svc(Request()))
    }
    assert(dtab == Some("/foo=>/bar"))
  }

  test("Headers.Ctx.serverModule uses strictest Deadline") {
    @volatile var deadline: Option[Deadline] = None
    val svc = {
      val sf = ServiceFactory.const(Service.mk[Request, Response] { _ =>
        deadline = Deadline.current
        Future.value(Response())
      })
      val stk = Headers.Ctx.serverModule +: Stack.leaf(Stack.Role("sf"), sf)
      await(stk.make(Stack.Params.empty)())
    }

    val req = Request()
    val now = Time.now
    Headers.Ctx.Deadline.set(
      req.headerMap,
      Deadline(now + 1.second, now + 10.seconds)
    )
    req.headerMap.add(
      Headers.Ctx.Deadline.Key,
      Headers.Ctx.Deadline.write(Deadline(now + 2.second, now + 8.seconds))
    )
    Contexts.broadcast.let(Deadline, Deadline(now, now + 2.seconds)) {
      val _ = await(svc(req))
    }
    assert(deadline == Some(Deadline(now + 2.second, now + 2.seconds)))
  }

  test("Headers.Ctx.clientModule sets Deadline") {
    @volatile var deadline: Option[Deadline] = None
    val svc = {
      val sf = ServiceFactory.const(Service.mk[Request, Response] { req =>
        deadline = Headers.Ctx.Deadline.get(req.headerMap)
        Future.value(Response())
      })
      val stk = Headers.Ctx.clientModule +: Stack.leaf(Stack.Role("sf"), sf)
      await(stk.make(Stack.Params.empty)())
    }

    val req = Request()
    val dl = Deadline(Time.now, Time.now + 5.seconds)
    Contexts.broadcast.let(Deadline, dl) {
      Headers.Ctx.Deadline.set(req.headerMap, dl)
      val _ = await(svc(req))
    }
    assert(req.headerMap.contains(Headers.Ctx.Deadline.Key))
    assert(deadline == Some(dl))
  }

  test("Headers.Ctx.clientModule omits Deadline if not set") {
    @volatile var deadline: Option[Deadline] = None
    val svc = {
      val sf = ServiceFactory.const(Service.mk[Request, Response] { req =>
        deadline = Headers.Ctx.Deadline.get(req.headerMap)
        Future.value(Response())
      })
      val stk = Headers.Ctx.clientModule +: Stack.leaf(Stack.Role("sf"), sf)
      await(stk.make(Stack.Params.empty)())
    }

    val req = Request()
    val _ = await(svc(req))
    assert(!req.headerMap.contains(Headers.Ctx.Deadline.Key))
    assert(deadline == None)
  }

  test("PathFilter encodes Dst.Path on downstream requests") {
    @volatile var path: Option[String] = None
    val svc = {
      val sf = ServiceFactory.const(Service.mk[Request, Response] { req =>
        path = req.headerMap.get(Headers.Dst.Path)
        Future.value(Response())
      })
      val stk = Headers.Dst.PathFilter.module +: Stack.leaf(Stack.Role("sf"), sf)
      await(stk.make(Stack.Params.empty + Dst.Path(Path.read("/freedom")))())
    }
    val _ = await(svc(Request()))
    assert(path == Some("/freedom"))
  }

  test("BoundFilter encodes Dst.Bound on downstream requests") {
    @volatile var id, residual: Option[String] = None
    val svc = {
      val sf = ServiceFactory.const(Service.mk[Request, Response] { req =>
        id = req.headerMap.get(Headers.Dst.Bound)
        residual = req.headerMap.get(Headers.Dst.Residual)
        Future.value(Response())
      })
      val stk = Headers.Dst.BoundFilter.module +: Stack.leaf(Stack.Role("sf"), sf)
      val bound = Dst.Bound(Var.value(Addr.Pending), Path.Utf8("id"), Path.Utf8("residual"))
      await(stk.make(Stack.Params.empty + bound)())
    }
    val _ = await(svc(Request()))
    assert(id == Some("/id"))
    assert(residual == Some("/residual"))
  }

  test("BoundFilter encodes Dst.Bound on downstream requests: omits empty residual") {
    @volatile var id, residual: Option[String] = None
    val svc = {
      val sf = ServiceFactory.const(Service.mk[Request, Response] { req =>
        id = req.headerMap.get(Headers.Dst.Bound)
        residual = req.headerMap.get(Headers.Dst.Residual)
        Future.value(Response())
      })
      val stk = Headers.Dst.BoundFilter.module +: Stack.leaf(Stack.Role("sf"), sf)
      val bound = Dst.Bound(Var.value(Addr.Pending), Path.Utf8("id"))
      await(stk.make(Stack.Params.empty + bound)())
    }
    val _ = await(svc(Request()))
    assert(id == Some("/id"))
    assert(residual == None)
  }
}
