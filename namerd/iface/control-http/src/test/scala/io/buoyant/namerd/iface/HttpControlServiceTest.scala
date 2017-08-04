package io.buoyant.namerd.iface

import java.net.InetSocketAddress

import com.twitter.conversions.time._
import com.twitter.finagle.Name.Bound
import com.twitter.finagle.http._
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.{Status => _, _}
import com.twitter.io.{Buf, Reader}
import com.twitter.util._
import io.buoyant.namer.{ConfiguredDtabNamer, RichActivity}
import io.buoyant.namerd._
import io.buoyant.namerd.storage.InMemoryDtabStore
import io.buoyant.test.Awaits
import org.scalatest.{Assertion, FunSuite}

class HttpControlServiceTest extends FunSuite with Awaits {

  val defaultDtabs = Map(
    "yeezus" -> Dtab.read("/yeezy => /yeezus"),
    "tlop" -> Dtab.read("/yeezy => /pablo")
  )

  val v1Stamp = DtabHandler.versionString(InMemoryDtabStore.InitialVersion)

  def newDtabStore(dtabs: Map[String, Dtab] = defaultDtabs): DtabStore =
    new InMemoryDtabStore(dtabs)

  def newService(store: DtabStore = newDtabStore()): Service[Request, Response] =
    new HttpControlService(store, { ns =>
      val dtab = store.observe(ns).map {
        case Some(versionedDtab) => versionedDtab.dtab
        case None => Dtab.empty
      }
      ConfiguredDtabNamer(dtab, Nil)
    }, Map.empty)

  def readAndAssert(reader: Reader, value: String): Assertion = {
    val buf = Buf.Utf8(value)
    readAndAssert(reader, buf)
  }

  def readAndAssert(reader: Reader, value: Buf): Assertion = {
    val buf = value.concat(HttpControlService.newline)
    val res = await(reader.read(buf.length)).flatMap(Buf.Utf8.unapply)
    assert(res == Some(buf).flatMap(Buf.Utf8.unapply))
  }

  test("dtab round-trips through json") {
    val dtab = Dtab.read("/tshirt => /suit")
    val json = Buf.Utf8("""[{"prefix":"/tshirt","dst":"/suit"}]""")
    assert(Json.read[Seq[Dentry]](json) == Return(dtab))
    assert(Json.write(dtab) == json)
  }

  test("GET /api/1/dtabs") {
    val req = Request()
    req.uri = "/api/1/dtabs"
    val service = newService()
    val rsp = Await.result(service(req), 1.second)
    assert(rsp.status == Status.Ok)
    assert(rsp.contentType == Some(MediaType.Json))
    val expected = Json.write(defaultDtabs.keys.toSeq.sorted).concat(HttpControlService.newline)
    assert(rsp.content == expected)
  }

  test("GET /api/1/dtabs watch") {
    val req = Request("/api/1/dtabs?watch=true")
    val store = newDtabStore()
    val service = newService(store)
    val rsp = Await.result(service(req), 1.second)
    assert(rsp.status == Status.Ok)
    assert(rsp.contentType == Some(MediaType.Json))

    readAndAssert(rsp.reader, """["yeezus","tlop"]""")

    await(store.create("graduation", Dtab.empty))
    readAndAssert(rsp.reader, """["yeezus","tlop","graduation"]""")

    rsp.reader.discard()
  }

  test("streaming response is de-duplicated") {
    val req = Request("/api/1/dtabs?watch=true")
    val (dtabs, witness) = Activity[Set[Ns]]()
    val store = new DtabStore {
      def update(ns: Ns, dtab: Dtab, version: DtabStore.Version): Future[Unit] = ???
      def put(ns: Ns, dtab: Dtab): Future[Unit] = ???
      def observe(ns: Ns): Activity[Option[VersionedDtab]] = ???
      def delete(ns: Ns): Future[Unit] = ???
      def list(): Activity[Set[Ns]] = dtabs
      def create(ns: Ns, dtab: Dtab): Future[Unit] = ???
    }
    val service = newService(store)
    witness.notify(Return(Set.empty))
    val rsp = Await.result(service(req), 1.second)
    assert(rsp.status == Status.Ok)
    assert(rsp.contentType == Some(MediaType.Json))

    readAndAssert(rsp.reader, """[]""")

    witness.notify(Return(Set("hello")))
    witness.notify(Return(Set("hello")))
    witness.notify(Return(Set("hello")))
    witness.notify(Return(Set("goodbye")))

    readAndAssert(rsp.reader, """["hello"]""")
    readAndAssert(rsp.reader, """["goodbye"]""")

    rsp.reader.discard()
  }

  test("GET /api/1/dtabs/") {
    val req = Request()
    req.uri = "/api/1/dtabs/"
    val service = newService()
    val rsp = Await.result(service(req), 1.second)
    assert(rsp.status == Status.Ok)
    assert(rsp.contentType == Some(MediaType.Json))
    val expected = Json.write(defaultDtabs.keys.toSeq.sorted).concat(HttpControlService.newline)
    assert(rsp.content == expected)
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
    val expected = Json.write(defaultDtabs("yeezus")).concat(HttpControlService.newline)
    assert(rsp.content == expected)
  }

  test("GET /api/1/dtabs/ns watch") {
    val req = Request("/api/1/dtabs/yeezus?watch=true")
    val store = newDtabStore()
    val service = newService(store)
    val rsp = Await.result(service(req), 1.second)
    assert(rsp.status == Status.Ok)
    assert(rsp.contentType == Some(MediaType.Json))

    readAndAssert(rsp.reader, Json.write(defaultDtabs("yeezus")))

    val newDtab = Dtab.read("/yeezy=>/kanye")
    await(store.put("yeezus", newDtab))
    readAndAssert(rsp.reader, Json.write(newDtab))

    rsp.reader.discard()
  }

  val acceptsAndMediaTypes = List(
    ("application/dtab", "application/dtab"),
    ("application/dtab;q=0.9", "application/dtab"),
    (MediaType.Txt, MediaType.Txt),
    (MediaType.Txt + ";q=0.9", MediaType.Txt)
  )

  for ((a, mt) <- acceptsAndMediaTypes)
    test(s"GET /api/1/dtabs/ns exists; accept $a") {
      val req = Request()
      req.uri = "/api/1/dtabs/yeezus"
      req.accept = Seq(a, MediaType.Json)
      val service = newService()
      val rsp = Await.result(service(req), 1.second)
      assert(rsp.status == Status.Ok)
      assert(rsp.contentType == Some(mt))
      assert(rsp.headerMap("ETag") == v1Stamp)
      assert(rsp.contentString == defaultDtabs("yeezus").show + "\n")
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
    MediaType.Json + ";charset=UTF-8" -> """[{"prefix":"/yeezy","dst":"/kanye"}]""",
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
      val result = Await.result(store.observe("graduation").toFuture)
      assert(result.get.dtab == Dtab.read("/yeezy=>/kanye"))
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
      val result = Await.result(store.observe("yeezus").toFuture)
      assert(result.get.dtab == Dtab.read("/yeezy=>/kanye"))
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
      val result = Await.result(store.observe("yeezus").toFuture)
      assert(result.get.dtab == Dtab.read("/yeezy=>/kanye"))
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
      val result = Await.result(store.observe("yeezus").toFuture)
      assert(result.get.dtab == Dtab.read("/yeezy=>/yeezus"))
    }
  }

  def interpreter = {
    val (act, witness) = Activity[NameTree[Bound]]()
    val ni = new NameInterpreter {
      override def bind(dtab: Dtab, path: Path): Activity[NameTree[Bound]] = act
    }
    (ni, witness)
  }

  test("bind") {
    val (ni, witness) = interpreter
    def delegate(ns: Ns): NameInterpreter = {
      assert(ns == "default")
      ni
    }
    val service = new HttpControlService(NullDtabStore, delegate, Map.empty)
    val bound = "/#/io.l5d.namer/foo"
    witness.notify(Return(NameTree.Leaf(Name.Bound(Var(Addr.Bound(Address(1))), Path.read(bound)))))

    val resp = await(service(Request("/api/1/bind/default?path=/foo")))
    assert(resp.status == Status.Ok)
    assert(resp.contentString.replaceAllLiterally("\n", "") ==
      """
        |{"type":"leaf",
        |"bound":{"addr":{"type":"bound","addrs":[{"ip":"127.0.0.1","port":1,"meta":{}}],"meta":{}},
        |"id":"/#/io.l5d.namer/foo","path":"/"}}
        |""".stripMargin.replaceAllLiterally("\n", ""))
  }

  test("bind with an extra dtab") {
    val ni = new NameInterpreter {
      override def bind(dtab: Dtab, path: Path): Activity[NameTree[Bound]] = {
        assert(dtab.show == "/foo=>/bar")
        val NameTree.Leaf(Name.Path(p)) = dtab.lookup(path)
        Activity.value(NameTree.Leaf(Name.Bound(Var(Addr.Bound(Address(1))), Path.read("/#/io.l5d.namer") ++ p)))
      }
    }

    def delegate(ns: Ns): NameInterpreter = {
      assert(ns == "default")
      ni
    }
    val service = new HttpControlService(NullDtabStore, delegate, Map.empty)

    val resp = await(service(Request("/api/1/bind/default?path=/foo&dtab=/foo=>/bar")))
    assert(resp.status == Status.Ok)
    assert(resp.contentString.replaceAllLiterally("\n", "") ==
      """
        |{"type":"leaf",
        |"bound":{"addr":{"type":"bound","addrs":[{"ip":"127.0.0.1","port":1,"meta":{}}],"meta":{}},
        |"id":"/#/io.l5d.namer/bar","path":"/"}}
        |""".stripMargin.replaceAllLiterally("\n", ""))
  }

  test("bind watch") {
    val (ni, witness) = interpreter
    def delegate(ns: Ns): NameInterpreter = {
      assert(ns == "default")
      ni
    }
    val service = new HttpControlService(NullDtabStore, delegate, Map.empty)
    val resp = await(service(Request("/api/1/bind/default?path=/foo&watch=true")))

    val bound = Path.read("/#/io.l5d.namer/foo")
    witness.notify(Return(NameTree.Leaf(Name.Bound(Var(Addr.Bound(Address(1))), bound))))
    readAndAssert(
      resp.reader,
      """
        |{"type":"leaf",
        |"bound":{"addr":{"type":"bound","addrs":[{"ip":"127.0.0.1","port":1,"meta":{}}],"meta":{}},
        |"id":"/#/io.l5d.namer/foo","path":"/"}}""".stripMargin.replaceAllLiterally("\n", "")
    )

    witness.notify(Return(NameTree.Neg))
    readAndAssert(
      resp.reader,
      """
        |{"type":"neg"}""".stripMargin.replaceAllLiterally("\n", "")
    )

    val bound2 = Path.read("/#/io.l5d.namer/bar")
    witness.notify(Return(NameTree.Leaf(Name.Bound(Var(Addr.Bound(Address(1))), bound2))))
    readAndAssert(
      resp.reader,
      """
        |{"type":"leaf",
        |"bound":{"addr":{"type":"bound","addrs":[{"ip":"127.0.0.1","port":1,"meta":{}}],"meta":{}},
        |"id":"/#/io.l5d.namer/bar","path":"/"}}""".stripMargin.replaceAllLiterally("\n", "")
    )

    resp.reader.discard()
  }

  test("bind an invalid path") {
    val service = newService(NullDtabStore)
    val resp = await(service(Request("/api/1/bind/default?path=invalid")))
    assert(resp.status == Status.BadRequest)
  }

  test("errors are printed") {
    val (ni, witness) = interpreter
    def delegate(ns: Ns): NameInterpreter = {
      assert(ns == "default")
      ni
    }
    val service = new HttpControlService(NullDtabStore, delegate, Map.empty)
    val resp = await(service(Request("/api/1/bind/default?path=/foo&watch=true")))

    val bound = "/io.l5d.namer/foo"
    witness.notify(Throw(new Exception("error")))
    readAndAssert(resp.reader, "error")

    assert(await(resp.reader.read(0)) == None)

    resp.reader.discard()
  }

  test("addr") {
    val (nameTree, witness) = Activity[NameTree[Name]]()
    val namer = new Namer {
      override def lookup(path: Path): Activity[NameTree[Name]] = nameTree
    }
    val prefix = Path.read("/#/io.l5d.namer")
    val service = new HttpControlService(NullDtabStore, _ => null, Map(prefix -> namer))
    val name = prefix ++ Path.read("/foo")

    val addr = Var[Addr](Addr.Pending)
    witness.notify(Return(NameTree.Leaf(Name.Bound(addr, name))))
    addr() = Addr.Bound(Address(1))

    val resp = await(service(Request(s"/api/1/addr/default?path=${name.show}")))

    assert(resp.status == Status.Ok)
    assert(resp.contentString.replaceAllLiterally("\n", "") ==
      """
        |{"type":"bound",
        |"addrs":[{"ip":"127.0.0.1","port":1,"meta":{}}],"meta":{}}
        |""".stripMargin.replaceAllLiterally("\n", ""))
  }

  test("addr watch") {
    val (nameTree, witness) = Activity[NameTree[Name]]()
    val namer = new Namer {
      override def lookup(path: Path): Activity[NameTree[Name]] = nameTree
    }
    val prefix = Path.read("/io.l5d.namer")
    val service = new HttpControlService(NullDtabStore, _ => null, Map(prefix -> namer))
    val id = "/foo"
    val resp = await(service(Request(s"/api/1/addr/default?path=${prefix.show}$id&watch=true")))
    val addr = Var[Addr](Addr.Bound(Address(1)))
    witness.notify(Return(NameTree.Leaf(Name.Bound(addr, id))))

    readAndAssert(
      resp.reader,
      """
        |{"type":"bound",
        |"addrs":[{"ip":"127.0.0.1","port":1,"meta":{}}],"meta":{}}
        |""".stripMargin.replaceAllLiterally("\n", "")
    )

    addr() = Addr.Bound(Address(1), Address(2))
    readAndAssert(
      resp.reader,
      """
        |{"type":"bound",
        |"addrs":[{"ip":"127.0.0.1","port":1,"meta":{}},{"ip":"127.0.0.1","port":2,"meta":{}}],"meta":{}}
        |""".stripMargin.replaceAllLiterally("\n", "")
    )

    witness.notify(Return(NameTree.Neg))
    readAndAssert(
      resp.reader,
      """
        |{"type":"neg"}
        |""".stripMargin.replaceAllLiterally("\n", "")
    )

    val addr2 = Var[Addr](Addr.Bound(Address(3)))
    witness.notify(Return(NameTree.Leaf(Name.Bound(addr2, id))))
    readAndAssert(
      resp.reader,
      """
        |{"type":"bound",
        |"addrs":[{"ip":"127.0.0.1","port":3,"meta":{}}],"meta":{}}
        |""".stripMargin.replaceAllLiterally("\n", "")
    )

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

  test("delegate a path given a namespace") {
    val service = newService()
    val resp = await(service(Request("/api/1/delegate/yeezus?path=/yeezy")))
    assert(resp.status == Status.Ok)
    assert(resp.contentString == """
                                   |{
                                   |  "type":"delegate",
                                   |  "path":"/yeezy",
                                   |  "delegate":{
                                   |    "type":"neg",
                                   |    "path":"/yeezus",
                                   |    "dentry":{
                                   |      "prefix":"/yeezy",
                                   |      "dst":"/yeezus"
                                   |    }
                                   |  }
                                   |}""".stripMargin.replaceAll("\\s", ""))
  }

  test("delegate a path given a dtab") {
    val service = newService()
    val resp = await(service(Request("/api/1/delegate?dtab=/foo=>/bar&path=/foo")))
    assert(resp.status == Status.Ok)
    assert(resp.contentString == """
                                   |{
                                   |  "type":"delegate",
                                   |  "path":"/foo",
                                   |  "delegate":{
                                   |    "type":"neg",
                                   |    "path":"/bar",
                                   |    "dentry":{
                                   |      "prefix":"/foo",
                                   |      "dst":"/bar"
                                   |    }
                                   |  }
                                   |}""".stripMargin.replaceAll("\\s", ""))
  }

  test("delegate a path given a namespace and a dtab") {
    val service = newService()
    val resp = await(service(Request("/api/1/delegate/yeezus?path=/yeezy&dtab=/yeezy=>/bar")))
    assert(resp.status == Status.Ok)
    assert(resp.contentString == """
                                   |{
                                   |  "type":"alt",
                                   |  "path":"/yeezy",
                                   |  "alt":[
                                   |    {
                                   |      "type": "neg",
                                   |      "path": "/bar",
                                   |      "dentry":{
                                   |        "prefix":"/yeezy",
                                   |        "dst":"/bar"
                                   |      }
                                   |    },
                                   |    {
                                   |      "type": "neg",
                                   |      "path": "/yeezus",
                                   |      "dentry": {
                                   |        "prefix": "/yeezy",
                                   |        "dst":"/yeezus"
                                   |      }
                                   |    }
                                   |  ]
                                   |}""".stripMargin.replaceAll("\\s", ""))
  }

  test("resolve w/ dtab") {
    val prefix = Path.read("/io.l5d.namer")

    val ni = new NameInterpreter {
      override def bind(dtab: Dtab, path: Path): Activity[NameTree[Bound]] = {
        val idStr = s"${prefix.show}${dtab.lookup(path).show}"
        Activity.value(NameTree.Leaf(Name.Bound(Var(null), Path.read(idStr))))
      }
    }

    def delegate(ns: Ns): NameInterpreter = ni

    val (nameTreeAct, witness) = Activity[NameTree[Name]]()
    val namer = new Namer {
      override def lookup(path: Path): Activity[NameTree[Name]] = nameTreeAct
    }

    val service = new HttpControlService(newDtabStore(), delegate, Map(prefix -> namer))

    val id = "/foo"

    val addr = Var[Addr](Addr.Pending)
    witness.notify(Return(NameTree.Leaf(Name.Bound(addr, id))))
    val isa = new InetSocketAddress("127.0.0.1", 1)
    val inet = Address.Inet(isa, Addr.Metadata(("isa-meta", "isa-data"))).asInstanceOf[Address]
    addr() = Addr.Bound(Set(inet), Addr.Metadata(("bound-meta", "bound-data")))

    val resp = await(service(Request(s"/api/1/resolve/default?path=/yeezy&dtab=/yeezy=>/foo")))

    assert(resp.status == Status.Ok)
    assert(resp.contentString ==
      """
        |{
        |  "type":"bound",
        |  "addrs":[
        |    {"ip":"127.0.0.1","port":1,"meta":{"isa-meta":"isa-data"}}
        |  ],
        |  "meta":{
        |    "bound-meta":"bound-data"
        |  }
        |}""".stripMargin.replaceAll("\\s", "") + "\n")

  }

  test("resolve watch") {
    val prefix = Path.read("/io.l5d.namer")

    val ni = new NameInterpreter {
      override def bind(dtab: Dtab, path: Path): Activity[NameTree[Bound]] = {
        val idsStr = s"${prefix.show}${dtab.lookup(path).show}"
        Activity.value(NameTree.Leaf(Name.Bound(Var(null), Path.read(idsStr))))
      }
    }

    def delegate(ns: Ns): NameInterpreter = ni

    val (nameTreeAct, witness) = Activity[NameTree[Name]]()
    val namer = new Namer {
      override def lookup(path: Path): Activity[NameTree[Name]] = nameTreeAct
    }

    val service = new HttpControlService(newDtabStore(), delegate, Map(prefix -> namer))

    val id = "/foo"

    val addr = Var[Addr](Addr.Pending)
    witness.notify(Return(NameTree.Leaf(Name.Bound(addr, id))))

    val resp = await(service(Request(s"/api/1/resolve/default?path=/yeezy&dtab=/yeezy=>/foo&watch=true")))

    val isa1 = new InetSocketAddress("127.0.0.1", 1)
    val inet1 = Address.Inet(isa1, Addr.Metadata(("isa-meta-1", "isa-data-1"))).asInstanceOf[Address]
    addr() = Addr.Bound(Set(inet1), Addr.Metadata(("bound-meta", "bound-data")))

    readAndAssert(
      resp.reader,
      """
        |{
        |  "type":"bound",
        |  "addrs":[
        |    {"ip":"127.0.0.1","port":1,"meta":{"isa-meta-1":"isa-data-1"}}
        |  ],
        |  "meta":{
        |    "bound-meta":"bound-data"
        |  }
        |}""".stripMargin.replaceAll("\\s", "")
    )

    val isa2 = new InetSocketAddress("127.0.0.1", 2)
    val inet2 = Address.Inet(isa2, Addr.Metadata(("isa-meta-2", "isa-data-2"))).asInstanceOf[Address]
    addr() = Addr.Bound(Set(inet1, inet2), Addr.Metadata(("bound-meta", "bound-data")))
    readAndAssert(
      resp.reader,
      """
        |{
        |  "type":"bound",
        |  "addrs":[
        |    {"ip":"127.0.0.1","port":1,"meta":{"isa-meta-1":"isa-data-1"}},
        |    {"ip":"127.0.0.1","port":2,"meta":{"isa-meta-2":"isa-data-2"}}
        |  ],
        |  "meta":{
        |    "bound-meta":"bound-data"
        |  }
        |}""".stripMargin.replaceAll("\\s", "")
    )

    witness.notify(Return(NameTree.Neg))
    readAndAssert(
      resp.reader,
      """
        |{
        |  "type":"neg"
        |}""".stripMargin.replaceAll("\\s", "")
    )

    resp.reader.discard()
  }

  test("resolve an invalid path") {
    val service = newService(NullDtabStore)
    val resp = await(service(Request("/api/1/resolve/default?path=invalid")))
    assert(resp.status == Status.BadRequest)
  }
}
