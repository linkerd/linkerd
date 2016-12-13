package io.buoyant.namer

import com.twitter.finagle._
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.util.LoadService
import com.twitter.util.{Activity, Future, Var}
import io.buoyant.config.Parser
import io.buoyant.test.FunSuite

class PortNamerTest extends FunSuite {

  test("service registration") {
    assert(LoadService[NamerInitializer]().exists(_.isInstanceOf[PortNamerInitializer]))
  }

  def namer = {
    val yaml = """
                 |kind: io.l5d.port
               """.stripMargin
    val mapper = Parser.objectMapper(yaml, Iterable(Seq(PortNamerInitializer)))
    val config = mapper.readValue[NamerConfig](yaml).asInstanceOf[PortNamerConfig]
    config.mk(Stack.Params.empty)
  }

  test("replaces port number") {

    val ni = new NameInterpreter {
      override def bind(
        dtab: Dtab,
        path: Path
      ): Activity[NameTree[Name.Bound]] = Activity.value(
        NameTree.Leaf(Name.Bound(Var(Addr.Bound(
          Address("1.1.1.1", 7000),
          Address("2.2.2.2", 8000),
          Address("3.3.3.3", 9000)
        )), path))
      )
    }

    NameInterpreter.setGlobal(ni)

    val act = namer.bind(NameTree.Leaf(Path.read("/4141/#/foo/bar")))
    val tree = await(act.values.toFuture.flatMap(Future.const))
    assert(tree == NameTree.Leaf(Name.Bound(Var(Addr.Bound(
      Address("1.1.1.1", 4141),
      Address("2.2.2.2", 4141),
      Address("3.3.3.3", 4141)
    )), Path.read("/#/io.l5d.port/4141/#/foo/bar"))))
  }

}
