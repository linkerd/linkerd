package io.buoyant.namerd.iface

import com.twitter.conversions.time._
import com.twitter.finagle.Name.Bound
import com.twitter.finagle.http._
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.{Status => _, _}
import com.twitter.io.{Buf, Reader}
import com.twitter.util._
import io.buoyant.namer.ConfiguredNamersInterpreter
import io.buoyant.namerd.storage.InMemoryDtabStore
import io.buoyant.namerd.{DtabStore, NullDtabStore}
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class HttpControlServiceTest extends FunSuite with Awaits {

  override def defaultWait: Duration = 2.seconds

  val defaultDtabs = Map(
    "yeezus" -> Dtab.read("/yeezy => /yeezus"),
    "tlop" -> Dtab.read("/yeezy => /pablo")
  )

  val v1Stamp = HttpControlService.versionString(InMemoryDtabStore.InitialVersion)

  def newDtabStore(dtabs: Map[String, Dtab] = defaultDtabs): DtabStore =
    new InMemoryDtabStore(dtabs)

  def newService(store: DtabStore = newDtabStore()): Service[Request, Response] =
    new HttpControlService(store, _ => ConfiguredNamersInterpreter(Nil))

  test("dtab round-trips through json") {
    val dtab = Dtab.read("/tshirt => /suit")
    val json = Buf.Utf8("""[{"prefix":"/tshirt","dst":"/suit"}]""")
    assert(HttpControlService.Json.read[Seq[Dentry]](json) == Return(dtab))
    assert(HttpControlService.Json.write(dtab) == json)
  }

  test("GET /api/1/dtabs") {
    val req = Request()
    req.uri = "/api/1/dtabs"
    val service = newService()
    val rsp = Await.result(service(req), 1.second)
    assert(rsp.status == Status.Ok)
    assert(rsp.contentType == Some(MediaType.Json))
    assert(rsp.content == HttpControlService.Json.write(defaultDtabs.keys))
  }

  test("GET /api/1/dtabs/") {
    val req = Request()
    req.uri = "/api/1/dtabs/"
    val service = newService()
    val rsp = Await.result(service(req), 1.second)
    assert(rsp.status == Status.Ok)
    assert(rsp.contentType == Some(MediaType.Json))
    assert(rsp.content == HttpControlService.Json.write(defaultDtabs.keys))
  }

  test("GET /api/1/dtabsexpialidocious") {
    val req = Request()
    req.uri = "/api/1/dtabsexpialidocious"
    val service = newService()
    val rsp = Await.result(service(req), 1.second)
    assert(rsp.status == Status.NotFound)
  }

  test("GET /api/1/dtabs/ns exists") {
    val req = Request()
    req.uri = "/api/1/dtabs/yeezus"
    val service = newService()
    val rsp = Await.result(service(req), 1.second)
    assert(rsp.status == Status.Ok)
    assert(rsp.contentType == Some(MediaType.Json))
    assert(rsp.headerMap("ETag") == v1Stamp)
    assert(rsp.content == HttpControlService.Json.write(defaultDtabs("yeezus")))
  }

  for (ct <- Seq("application/dtab", MediaType.Txt))
    test(s"GET /api/1/dtabs/ns exists; accept $ct") {
      val req = Request()
      req.uri = "/api/1/dtabs/yeezus"
      req.accept = Seq(ct, MediaType.Json)
      val service = newService()
      val rsp = Await.result(service(req), 1.second)
      assert(rsp.status == Status.Ok)
      assert(rsp.contentType == Some(ct))
      assert(rsp.headerMap("ETag") == v1Stamp)
      assert(rsp.contentString == defaultDtabs("yeezus").show)
    }

  test("GET /api/1/dtabs/ns not exists") {
    val req = Request()
    req.uri = "/api/1/dtabs/graduation"
    req.accept = Seq("application/dtab", MediaType.Json)
    val service = newService()
    val rsp = Await.result(service(req), 1.second)
    assert(rsp.status == Status.NotFound)
  }

  test("POST /api/1/dtabs/ns; no content-type") {
    val req = Request()
    req.method = Method.Post
    req.uri = "/api/1/dtabs/graduation"
    req.contentString = "/yeezy => /kanye"
    val service = newService()
    val rsp = Await.result(service(req), 1.second)
    assert(rsp.status == Status.BadRequest)
  }

  test("HEAD /api/1/dtabs/ns returns ETag") {
    val req = Request()
    req.method = Method.Head
    req.uri = "/api/1/dtabs/yeezus"
    val service = newService()
    val rsp = Await.result(service(req), 1.second)
    assert(rsp.status == Status.Ok)
    assert(rsp.headerMap("ETag") == v1Stamp)
    assert(rsp.contentLength == None)
  }

  val data = Map(
    MediaType.Json -> """[{"prefix":"/yeezy","dst":"/kanye"}]""",
    MediaType.Txt -> "/yeezy => /kanye",
    "application/dtab" -> "/yeezy => /kanye"
  )
  for ((ct, body) <- data)
    test(s"POST /api/1/dtabs/ns; $ct") {
      val req = Request()
      req.method = Method.Post
      req.uri = "/api/1/dtabs/graduation"
      req.contentType = ct
      req.contentString = body
      val store = newDtabStore()
      val service = newService(store)
      val rsp = Await.result(service(req), 1.second)
      assert(rsp.status == Status.NoContent)
      val result = Await.result(store.observe("graduation").values.toFuture())
      assert(result.get.get.dtab == Dtab.read("/yeezy=>/kanye"))
    }

  for ((ct, body) <- data) {
    test(s"PUT without stamp; $ct") {
      val req = Request()
      req.method = Method.Put
      req.uri = s"/api/1/dtabs/yeezus"
      req.contentType = ct
      req.contentString = body
      val store = newDtabStore()
      val service = newService(store)
      val rsp = Await.result(service(req), 1.second)
      assert(rsp.status == Status.NoContent)
      val result = Await.result(store.observe("yeezus").values.toFuture())
      assert(result.get.get.dtab == Dtab.read("/yeezy=>/kanye"))
    }

    test(s"PUT with valid stamp; $ct") {
      val req = Request()
      req.method = Method.Put
      req.uri = s"/api/1/dtabs/yeezus"
      req.headerMap.add("If-Match", v1Stamp)
      req.contentType = ct
      req.contentString = body
      val store = newDtabStore()
      val service = newService(store)
      val rsp = Await.result(service(req), 1.second)
      assert(rsp.status == Status.NoContent)
      val result = Await.result(store.observe("yeezus").values.toFuture())
      assert(result.get.get.dtab == Dtab.read("/yeezy=>/kanye"))
    }

    test(s"PUT with invalid stamp; $ct") {
      val req = Request()
      req.method = Method.Put
      req.uri = "/api/1/dtabs/yeezus"
      req.headerMap.add("If-Match", "yolo")
      req.contentType = ct
      req.contentString = body
      val store = newDtabStore()
      val service = newService(store)
      val rsp = Await.result(service(req), 1.second)
      assert(rsp.status == Status.PreconditionFailed)
      val result = Await.result(store.observe("yeezus").values.toFuture())
      assert(result.get.get.dtab == Dtab.read("/yeezy=>/yeezus"))
    }
  }

  def interpreter = {
    val (act, witness) = Activity[NameTree[Bound]]()
    val ni = new NameInterpreter {
      override def bind(dtab: Dtab, path: Path): Activity[NameTree[Bound]] = act
    }
    (ni, witness)
  }

  def readAndAssert(reader: Reader, value: String): Unit = {
    val buf = Buf.Utf8(value + "\n")
    val res = await(reader.read(buf.length)).flatMap(Buf.Utf8.unapply)
    assert(res == Some(buf).flatMap(Buf.Utf8.unapply))
  }

  test("bind") {
    val (ni, witness) = interpreter
    val service = new HttpControlService(NullDtabStore, _ => ni)
    val resp = await(service(Request("/api/1/bind/default?path=/foo")))

    val bound = "/io.l5d.namer/foo"
    witness.notify(Return(NameTree.Leaf(Name.Bound(Var(null), bound))))
    readAndAssert(resp.reader, bound)

    witness.notify(Return(NameTree.Neg))
    val neg = "~"
    readAndAssert(resp.reader, neg)

    val bound2 = "/io.l5d.namer/bar"
    witness.notify(Return(NameTree.Leaf(Name.Bound(Var(null), bound2))))
    readAndAssert(resp.reader, bound2)

    resp.reader.discard()
  }

  test("bind an invalid path") {
    val service = newService(NullDtabStore)
    val resp = await(service(Request("/api/1/bind/default?path=invalid")))
    assert(resp.status == Status.BadRequest)
  }

  test("addr") {
    val (ni, witness) = interpreter
    val service = new HttpControlService(NullDtabStore, _ => ni)
    val id = "/io.l5d.namer/foo"
    val resp = await(service(Request(s"/api/1/addr/default?path=$id")))
    val addr = Var[Addr](Addr.Pending)
    witness.notify(Return(NameTree.Leaf(Name.Bound(addr, id))))

    addr() = Addr.Bound(Address(1))
    readAndAssert(resp.reader, "Bound(0.0.0.0/0.0.0.0:1)")

    addr() = Addr.Bound(Address(1), Address(2))
    readAndAssert(resp.reader, "Bound(0.0.0.0/0.0.0.0:1,0.0.0.0/0.0.0.0:2)")

    witness.notify(Return(NameTree.Neg))
    readAndAssert(resp.reader, "Neg")

    val addr2 = Var[Addr](Addr.Pending)
    witness.notify(Return(NameTree.Leaf(Name.Bound(addr2, id))))
    addr2() = Addr.Bound(Address(3))
    readAndAssert(resp.reader, "Bound(0.0.0.0/0.0.0.0:3)")

    resp.reader.discard()
  }

  test("addr an invalid path") {
    val service = newService(NullDtabStore)
    val resp = await(service(Request("/api/1/addr/default?path=invalid")))
    assert(resp.status == Status.BadRequest)
  }

  test("delegate an invalid path") {
    val service = newService(NullDtabStore)
    val resp = await(service(Request("/api/1/delegate/default?path=invalid")))
    assert(resp.status == Status.BadRequest)
  }
}
