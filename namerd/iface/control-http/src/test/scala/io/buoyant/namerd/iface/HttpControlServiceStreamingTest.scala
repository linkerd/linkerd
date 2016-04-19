package io.buoyant.namerd.iface

import com.twitter.finagle.Name.Bound
import com.twitter.finagle.http.Request
import com.twitter.finagle._
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.io.{Reader, Buf}
import com.twitter.util.{Return, Var, Activity}
import io.buoyant.namerd.NullDtabStore
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class HttpControlServiceStreamingTest extends FunSuite with Awaits {

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
    val resp = await(service(Request("/api/1/bind/default/foo")))

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

  test("addr") {
    val (ni, witness) = interpreter
    val service = new HttpControlService(NullDtabStore, _ => ni)
    val id = "/io.l5d.namer/foo"
    val resp = await(service(Request(s"/api/1/addr/default$id")))
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
}
