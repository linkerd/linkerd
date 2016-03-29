package io.buoyant.namerd

import com.twitter.finagle._
import com.twitter.util.{Activity, Await, Var}
import java.net.InetSocketAddress
import org.scalatest.FunSuite

class ConfiguredDtabNamerTest extends FunSuite {

  test("waits for dtab") {
    val dtabStates = Var[Activity.State[Dtab]](Activity.Pending)
    val interpreter = ConfiguredDtabNamer(Activity(dtabStates))
    @volatile var state: Activity.State[NameTree[Name.Bound]] = Activity.Pending
    val closer = interpreter.bind(Dtab.empty, Path.read("/foo/fah")).states.respond(state = _)
    try {
      assert(state == Activity.Pending)
      dtabStates() = Activity.Ok(Dtab.empty)
      assert(state == Activity.Ok(NameTree.Neg))
    } finally Await.result(closer.close())
  }

  test("updates on dtab change") {
    val dtabStates = Var[Activity.State[Dtab]](Activity.Ok(Dtab.empty))
    val inetNamer = Path.Utf8("#inet") -> new Namer {
      def lookup(path: Path): Activity[NameTree[Name]] = path match {
        case Path.Utf8(ip, port, rest@_*) =>
          val addr = Var.value(Addr.Bound(Address(new InetSocketAddress(ip, port.toInt))))
          val name = Name.Bound(addr, Path.Utf8("#inet", ip, port), Path.Utf8(rest: _*))
          Activity.value(NameTree.Leaf(name))
        case _ => Activity.value(NameTree.Neg)
      }
    }

    val interpreter = ConfiguredDtabNamer(Activity(dtabStates), inetNamer :: Nil)
    @volatile var state: Activity.State[NameTree[Name.Bound]] = Activity.Pending
    val localDtab = Dtab.read("/foo => /bar")
    val closer = interpreter.bind(localDtab, Path.read("/foo/fah")).states.respond(state = _)
    try {
      assert(state == Activity.Ok(NameTree.Neg))
      dtabStates() = Activity.Ok(Dtab.read("/bar => /#inet/127.1/8080"))
      assert(state == Activity.Ok(NameTree.Leaf(Name.Bound(
        Var.value(Addr.Bound(Address(new InetSocketAddress("127.1", 8080)))),
        Path.read("/#inet/127.1/8080"),
        Path.read("/fah")
      ))))
    } finally Await.result(closer.close())
  }
}
