package io.buoyant.namer

import com.twitter.finagle._
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.util.LoadService
import com.twitter.util.{Activity, Future, Var}
import io.buoyant.config.Parser
import io.buoyant.test.FunSuite
import java.net.{InetAddress, InetSocketAddress}

class LocalhostNamerTest extends FunSuite {

  test("service registration") {
    assert(LoadService[NamerInitializer]().exists(_.isInstanceOf[LocalhostNamerInitializer]))
  }

  def namer = {
    val yaml = """
                 |kind: io.l5d.localhost
               """.stripMargin
    val mapper = Parser.objectMapper(yaml, Iterable(Seq(LocalhostNamerInitializer)))
    val config = mapper.readValue[NamerConfig](yaml).asInstanceOf[LocalhostNamerConfig]
    config.mk(Stack.Params.empty)
  }

  test("filters to localhost") {

    val isa = new InetSocketAddress(InetAddress.getLocalHost, 9000)

    val ni = new NameInterpreter {
      override def bind(
        dtab: Dtab,
        path: Path
      ): Activity[NameTree[Name.Bound]] = Activity.value(
        NameTree.Leaf(
          Name.Bound(
            Var(
              Addr.Bound(
                Address("1.1.1.1", 7000),
                Address("2.2.2.2", 8000),
                Address(isa)
              )
            ), path
          )
        )
      )
    }

    NameInterpreter.setGlobal(ni)

    val act = namer.bind(NameTree.Leaf(Path.read("/#/foo/bar")))
    val tree = await(act.values.toFuture.flatMap(Future.const))
    assert(
      tree == NameTree.Leaf(
        Name.Bound(
          Var(
            Addr.Bound(
              Address(isa)
            )
          ), Path.read("/#/io.l5d.localhost/#/foo/bar")
        )
      )
    )
  }
}
