package io.buoyant.etcd

import com.twitter.conversions.DurationOps._
import com.twitter.finagle.{Filter, Path, Service}
import com.twitter.finagle.http.{Message, Method, Request, Response, Status}
import com.twitter.io.Buf
import com.twitter.util.{Events => _, _}
import io.buoyant.test.{Exceptions, Events}
import org.scalatest._

class KeyTest extends FunSuite with Exceptions {

  private[this]type Params = Map[String, Seq[String]]

  private[this] def getParam(params: Params, param: String): Option[String] =
    params.get(param).flatMap(_.headOption)

  def writeJson[T](t: T): Buf = Buf.ByteArray.Owned(Etcd.mapper.writeValueAsBytes(t))

  /*
   * Mock the etcd server using the provided (simplified) request handler.
   * If the handler returns a NodeOp, it is properly encoded into the response.
   * If it returns a Version, it is encoded.
   * If it returns a Future, the value is treated as described above once the future is satisfied.
   */
  private[this] def mkClient(handle: PartialFunction[(Method, Path, Map[String, Seq[String]]), Any]) =
    Service.mk[Request, Response] { req =>
      val (path, params) = req.method match {
        case Method.Post | Method.Put =>
          val path = Path.read(req.uri)
          val params = req.params.keysIterator.map { k =>
            k -> req.params.getAll(k).toSeq
          }.toMap
          (path, params)

        case _ =>
          val path = Path.read(req.path)
          val params = req.params.keys.foldLeft(Map.empty[String, Seq[String]]) {
            case (params, k) => params + (k -> req.params.getAll(k).toSeq)
          }
          (path, params)
      }

      val rsp = Response()
      rsp.version = req.version

      val k = (req.method, path, params)
      if (handle.isDefinedAt(k)) {
        Future(handle(k)).flatMap(serve(req.method, rsp, _)) handle {
          case e@ApiError(code, _, _, index) =>
            rsp.status = code match {
              case ApiError.KeyNotFound => Status.NotFound
              case ApiError.NodeExist => Status.Forbidden
              case _ => Status.BadRequest
            }
            addState(Etcd.State(index), rsp)
            rsp.content = writeJson(e)
            rsp
        }
      } else {
        rsp.status = Status.InternalServerError
        info(s"method=${req.method} path=$path params=$params")
        Future.value(rsp)
      }
    }

  private[this] def mkEtcd(
    filter: Filter[Request, Response, Request, Response] = Filter.identity
  )(handle: PartialFunction[(Method, Path, Params), Any]) =
    new Etcd(filter andThen mkClient(handle))

  private[this] def mkKey(
    key: Path,
    filter: Filter[Request, Response, Request, Response] = Filter.identity
  )(handle: PartialFunction[(Method, Params), Any]): Key = {
    val uri = Path.Utf8("v2", "keys") ++ key
    val etcd = mkEtcd(filter) {
      case (method, `uri`, params) if handle.isDefinedAt(method, params) =>
        handle((method, params))
    }
    etcd.key(key)
  }

  private[this] def serve(method: Method, rsp: Response, v: Any): Future[Response] =
    v match {
      case f: Future[_] =>
        f.flatMap(serve(method, rsp, _))

      case Some(v) =>
        serve(method, rsp, v)

      case op: NodeOp =>
        rsp.content = writeJson(NodeOp.Json(op))
        addState(op.etcd, rsp)
        rsp.status = (rsp.status, method) match {
          case (Status.Ok, Method.Put | Method.Post) => Status.Created
          case (status, _) => status
        }
        Future.value(rsp)

      case v: Version =>
        rsp.content = writeJson(v)
        Future.value(rsp)

      case idk =>
        rsp.status = Status.InternalServerError
        rsp.contentString = idk.toString
        Future.value(rsp)
    }

  private[this] def addState(etcd: Etcd.State, msg: Message): Unit = {
    msg.headerMap("X-Etcd-Index") = etcd.index.toString
    msg.headerMap("X-Etcd-Cluster-Id") = etcd.clusterId
  }

  private[this] object Recursive {
    def unapply(params: Params): Option[Boolean] =
      Some(getParam(params, "recursive").exists(_ == "true"))
  }
  private[this] object DirParam {
    def unapply(params: Params): Option[Boolean] =
      Some(getParam(params, "dir").exists(_ == "true"))
  }
  private[this] object DirRecursive {
    def unapply(params: Params): Option[(Boolean, Boolean)] = {
      val DirParam(dir) = params
      val Recursive(recursive) = params
      Some((dir, recursive))
    }
  }

  private[this] object Watch {
    def unapply(params: Params): Option[(Boolean, Option[Long])] = {
      val Recursive(recursive) = params
      val idx = getParam(params, "wait").find(_ == "true").flatMap { _ =>
        getParam(params, "waitIndex") flatMap { waitIndex =>
          Try(waitIndex.toLong).toOption
        }
      }
      Some((recursive, idx))
    }
  }

  test("Etcd.version") {
    val uri = Path.Utf8("version")
    val version = Version("a.b.c.d", "a")
    val etcd = mkEtcd() { case (Method.Get, `uri`, _) => version }
    val v = etcd.version()
    assert(Await.result(v, 250.millis) == version)
  }

  test("Key.delete") {
    val path = Path.read("/some/test/path")
    val op = NodeOp(
      NodeOp.Action.Delete,
      Node.Data(path, 124, 100),
      Etcd.State(124),
      Some(Node.Data(path, 123, 100, value = Buf.Utf8("I like dogs")))
    )
    val key = mkKey(path) { case (Method.Delete, _) => op }
    val del = key.delete()
    assert(Await.result(del, 250.millis) == op)
  }

  test("Key.delete: dir") {
    val path = Path.read("/some/test/path")
    val op = NodeOp(
      NodeOp.Action.Delete,
      Node.Dir(path, 124, 100),
      Etcd.State(124),
      Some(Node.Dir(path, 123, 100))
    )

    val key = mkKey(path) { case (Method.Delete, DirParam(true)) => op }
    val del = key.delete(dir = true)
    assert(Await.result(del, 250.millis) == op)
  }

  test("Key.delete: tree") {
    val path = Path.read("/some/test/path")
    val op = NodeOp(
      NodeOp.Action.Delete,
      Node.Dir(path, 124, 100),
      Etcd.State(124),
      Some(Node.Dir(path, 123, 100, nodes = Seq(
        Node.Data(path ++ Path.Utf8("child"), 123, 123)
      )))
    )

    val key = mkKey(path) { case (Method.Delete, DirRecursive(true, true)) => op }
    val del = key.delete(dir = true, recursive = true)
    assert(Await.result(del, 250.millis) == op)
  }

  test("Key.get: data") {
    val data = Node.Data(Path.read("/some/test/path"), 123, 100, value = Buf.Utf8("I like dogs"))
    val op = NodeOp(NodeOp.Action.Get, data, Etcd.State(123))
    val key = mkKey(op.node.key) { case (Method.Get, _) => op }
    assert(Await.result(key.get(), 250.millis) == op)
  }

  test("Key.get: quorum") {
    val data = Node.Data(Path.read("/some/test/path"), 123, 100, value = Buf.Utf8("I like dogs"))
    val op = NodeOp(NodeOp.Action.Get, data, Etcd.State(123))
    val key = mkKey(op.node.key) {
      case (Method.Get, params) if getParam(params, "quorum").exists(_ == "true") => op
    }
    val get = key.get(quorum = true)
    assert(Await.result(get, 250.millis) == op)
  }

  test("Key.get: dir") {
    val op = NodeOp(
      NodeOp.Action.Get,
      Node.Dir(Path.read("/some"), 123, 100, nodes = Seq(
        Node.Dir(Path.read("/some/test"), 123, 100),
        Node.Data(Path.read("/some/data"), 111, 111)
      )),
      Etcd.State(123)
    )

    val key = mkKey(op.node.key) { case (Method.Get, _) => op }
    val get = key.get()
    assert(Await.result(get, 250.millis) == op)
  }

  test("Key.get: dir: recursive") {
    val op = NodeOp(
      NodeOp.Action.Get,
      Node.Dir(Path.read("/some"), 123, 100, nodes = Seq(
        Node.Dir(Path.read("/some/test"), 123, 100, nodes = Seq(
          Node.Data(Path.read("/some/test/path"), 123, 100)
        )),
        Node.Data(Path.read("/some/data"), 111, 111)
      )),
      Etcd.State(123)
    )

    val key = mkKey(op.node.key) {
      case (Method.Get, Recursive(true)) => op
    }
    val get = key.get(recursive = true)
    assert(Await.result(get, 250.millis) == op)
  }

  test("Key.set: data") {
    val op = NodeOp(NodeOp.Action.Set, Node.Data(Path.Utf8("k"), 1, 1, value = Buf.Utf8("v")), Etcd.State(1))
    val key = mkKey(op.node.key) {
      case (Method.Put, params) if getParam(params, "value").exists(_ == "v") => op
    }
    val set = key.set(Some(Buf.Utf8("v")))
    assert(Await.result(set, 250.millis) == op)
  }

  test("Key.set: data ttl") {
    val op = NodeOp(
      NodeOp.Action.Set,
      Node.Data(Path.Utf8("k"), 1, 1, value = Buf.Utf8("v")), Etcd.State(1)
    )
    val ttl = 17.minutes
    val key = mkKey(op.node.key) {
      case (Method.Put, params) =>
        assert(getParam(params, "value") == Some("v"))
        assert(getParam(params, "ttl") == Some(ttl.inSeconds.toString))
        assert(getParam(params, "prevExist") == Some("true"))
        op
    }
    val set = key.set(
      Some(Buf.Utf8("v")),
      ttl = Some(ttl),
      prevExist = true
    )
    assert(Await.result(set, 250.millis) == op)
  }

  test("Key.set: dir") {
    val op = NodeOp(NodeOp.Action.Set, Node.Dir(Path.Utf8("dir"), 1, 1), Etcd.State(1))
    val key = mkKey(op.node.key) {
      case (Method.Put, DirParam(true)) => op
    }
    val set = key.set(None)
    assert(Await.result(set, 250.millis) == op)
  }

  test("Key.compareAndSwap: fails requirement") {
    val key = mkKey(Path.Utf8("caskey")) {
      case (_, _) =>
        Future(fail("should not have called the web service"))
    }
    assertThrows[IllegalArgumentException] {
      key.compareAndSwap(Buf.Utf8("newval"))
    }
  }

  test("Key.compareAndSwap: prevIndex") {
    val path = Path.Utf8("caskey")
    val op = NodeOp(
      NodeOp.Action.CompareAndSwap,
      Node.Data(path, 124, 123, None, Buf.Utf8("newval")),
      Etcd.State(123),
      Some(Node.Data(path, 123, 123, None, Buf.Utf8("oldval")))
    )
    val params = Map(
      "prevIndex" -> Seq("123"),
      "value" -> Seq("newval")
    )
    val key = mkKey(path) { case (Method.Put, `params`) => op }
    val cas = key.compareAndSwap(Buf.Utf8("newval"), prevIndex = Some(123))
    val casOp = Await.result(cas, 250.millis)
    assert(casOp == op)
  }

  test("Key.compareAndSwap: prevValue") {
    val path = Path.Utf8("caskey")
    val op = NodeOp(
      NodeOp.Action.CompareAndSwap,
      Node.Data(path, 124, 123, None, Buf.Utf8("newval")),
      Etcd.State(123),
      Some(Node.Data(path, 123, 123, None, Buf.Utf8("oldval")))
    )
    val params = Map(
      "prevValue" -> Seq("oldval"),
      "value" -> Seq("newval")
    )
    val key = mkKey(path) { case (Method.Put, `params`) => op }
    val cas = key.compareAndSwap(Buf.Utf8("newval"), prevValue = Some(Buf.Utf8("oldval")))
    val casOp = Await.result(cas, 250.millis)
    assert(casOp == op)
  }

  test("Key.compareAndSwap: prevExist=false") {
    val path = Path.Utf8("caskey")
    val op = NodeOp(
      NodeOp.Action.CompareAndSwap,
      Node.Data(path, 124, 123, None, Buf.Utf8("newval")),
      Etcd.State(123),
      Some(Node.Data(path, 123, 123, None, Buf.Utf8("oldval")))
    )
    val params = Map(
      "prevExist" -> Seq("false"),
      "value" -> Seq("newval")
    )
    val key = mkKey(path) { case (Method.Put, `params`) => op }
    val cas = key.compareAndSwap(Buf.Utf8("newval"), prevExist = false)
    val casOp = Await.result(cas, 250.millis)
    assert(casOp == op)
  }

  test("Key.create: data") {
    val base = Path.Utf8("base")
    val op = NodeOp(NodeOp.Action.Create, Node.Data(Path.Utf8("base", "1"), 1, 1, None, Buf.Utf8("dogs")), Etcd.State(1))
    val key = mkKey(base) {
      case (Method.Put, params) if getParam(params, "value").exists(_ == "dogs") => op
    }
    val create = key.create(Some(Buf.Utf8("dogs")))
    assert(Await.result(create, 250.millis) == op)
  }

  test("Key.create: dir") {
    val base = Path.Utf8("base")
    val op = NodeOp(NodeOp.Action.Create, Node.Dir(Path.Utf8("base", "1"), 1, 1), Etcd.State(1))
    val key = mkKey(base) {
      case (Method.Put, DirParam(true)) => op
    }
    val create = key.create(None)
    assert(Await.result(create, 250.millis) == op)
  }

  test("Key.create: ttl") {
    val base = Path.Utf8("base")
    val ttl = 10.seconds
    val op = NodeOp(
      NodeOp.Action.Create,
      Node.Dir(Path.Utf8("base", "1"), 1, 1, Some(Node.Lease(ttl.fromNow, ttl))),
      Etcd.State(1)
    )
    val key = mkKey(base) {
      case (Method.Put, params) if getParam(params, "ttl").exists(_ == "10") => op
    }
    val create = key.create(None, Some(10.seconds))
    assert(Await.result(create, 250.millis) == op)
  }

  test("Key.events: recursively") {
    val base = Path.read("/base")
    val newKey = base ++ Path.Utf8("1")

    val ops = Seq(
      NodeOp(
        NodeOp.Action.Get,
        Node.Dir(base, 0, 0, None, Seq(Node.Data(base ++ Path.Utf8("bah"), 0, 0))),
        Etcd.State(0)
      ),
      NodeOp(
        NodeOp.Action.Create,
        Node.Dir(newKey, 1, 0, Some(Node.Lease(30.seconds.fromNow, 30.seconds))),
        Etcd.State(1)
      ),
      NodeOp(
        NodeOp.Action.Expire,
        Node.Dir(newKey, 2, 0, None),
        Etcd.State(2),
        Some(Node.Dir(newKey, 1, 0, None))
      )
    )
    val responses = (0 to ops.size).map(_ => new Promise[NodeOp])
    val requested = (0 until responses.size).map(_ => new Promise[Unit])

    @volatile var currentIndex = 0L
    val key = mkKey(base) {
      case (Method.Get, Watch(true, None)) if currentIndex < responses.length =>
        val idx = currentIndex.toInt
        requested(idx).setDone()
        responses(idx)

      case (Method.Get, Watch(true, Some(idx))) if idx < responses.length =>
        requested(idx.toInt).setDone()
        responses(idx.toInt)
    }

    val before = Events.take(4, key.events(recursive = true))
    val after = ops.zip(responses).zip(requested).zipWithIndex.foldLeft(before) {
      case (Events.None(), _) =>
        fail("events underflow")

      case (events, (((op, response), requested), idx)) =>
        currentIndex = op.etcd.index
        assert(requested.isDefined)
        response.setValue(op)
        val (state, nextEvents) = Await.result(events.next(), 250.millis)
        assert(state == Return(op))
        nextEvents
    }

    // closes properly
    assert(after.size == 1)
    assert(requested.last.isDefined)
    assert(!responses.last.isDefined)
    Await.result(after.close(), 250.millis)
    assert(responses.last.isInterrupted != None)
  }

  test("Key.events: old modifiedIndex") {
    val base = Path.read("/base")
    val newKey = base ++ Path.Utf8("keyed")

    val createdIndex, modifiedIndex = 123L
    val liveState = 234L

    val init, watch = new Promise[NodeOp]
    @volatile var waitingIndex: Option[Long] = None
    val key = mkKey(base) {
      case (Method.Get, Watch(false, None)) =>
        init

      case (Method.Get, Watch(false, Some(idx))) =>
        waitingIndex = Some(idx)
        watch
    }

    val events = key.events()
    val closable = events.respond(_ => ())
    assert(waitingIndex == None)
    init.setValue(NodeOp(
      NodeOp.Action.Get,
      Node.Data(newKey, modifiedIndex, createdIndex, None, Buf.Empty),
      Etcd.State(liveState)
    ))
    assert(waitingIndex == Some(liveState + 1))
    Await.result(closable.close(), 1.second)
    assert(watch.isInterrupted.isDefined)
  }
}
