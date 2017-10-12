package io.buoyant.interpreter.k8s

import com.twitter.finagle.Stack.Params
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.{Dtab, Service, http}
import com.twitter.finagle.util.LoadService
import com.twitter.io.{Buf, Writer}
import com.twitter.util.{Activity, Future, Promise, Return}
import io.buoyant.config.Parser
import io.buoyant.config.types.Port
import io.buoyant.k8s.v1
import io.buoyant.k8s.v1.ConfigMap
import io.buoyant.namer.{ConfiguredDtabNamer, InterpreterConfig, InterpreterInitializer, RichActivity}
import io.buoyant.test.{Awaits, FunSuite}
import org.scalatest.exceptions.TestFailedException
import org.scalatest.{Inside, OptionValues, TryValues}

class ConfigMapInterpreterTest extends FunSuite
  with Inside
  with TryValues
  with Awaits {

  object Rsps {
    val Get = Buf.Utf8(
      """|{
         |  "kind": "ConfigMap",
         |  "apiVersion": "v1",
         |  "metadata": {
         |    "name": "test-config",
         |    "namespace": "test",
         |    "selfLink": "/api/v1/namespaces/test/configmaps/test-config",
         |    "uid": "942fd048-996b-11e7-bfc0-42010af00004",
         |    "resourceVersion": "61622964",
         |    "creationTimestamp": "2017-09-14T16:41:38Z"
         |  },
         |  "data": {
         |    "test.dtab": "/svc =\u003e /$/inet/127.1/11111;\n"
         |  }
         |}
         |""".stripMargin
    )
    val Added = Buf.Utf8(
      """|{
         |  "type": "ADDED",
         |  "object": {
         |    "kind": "ConfigMap",
         |    "apiVersion": "v1",
         |    "metadata": {
         |      "name": "test-config",
         |      "namespace": "test",
         |      "selfLink": "/api/v1/namespaces/test/configmaps/test-config",
         |      "uid": "942fd048-996b-11e7-bfc0-42010af00004",
         |      "resourceVersion": "61622964",
         |      "creationTimestamp": "2017-09-14T16:41:38Z"
         |    },
         |    "data": {
         |      "test.dtab": "/svc => /$/inet/127.1/11111;\n"
         |    }
         |  }
         |}""".stripMargin
    )
    val UnrelatedAdded = Buf.Utf8(
      """|{
         |  "type": "MODIFIED",
         |  "object": {
         |    "kind": "ConfigMap",
         |    "apiVersion": "v1",
         |    "metadata": {
         |      "name": "test-config",
         |      "namespace": "test",
         |      "selfLink": "/api/v1/namespaces/test/configmaps/test-config",
         |      "uid": "942fd048-996b-11e7-bfc0-42010af00004",
         |      "resourceVersion": "61623524",
         |      "creationTimestamp": "2017-09-14T16:41:38Z"
         |    },
         |    "data": {
         |      "random.properties": "somethingUnrelated: true\n",
         |      "test.dtab": "/svc => /$/inet/127.1/11111;\n"
         |    }
         |  }
         |}""".stripMargin
    )
    val UnrelatedRemoved = Buf.Utf8(
      """|{
         |  "type": "MODIFIED",
         |  "object": {
         |    "kind": "ConfigMap",
         |    "apiVersion": "v1",
         |    "metadata": {
         |      "name": "test-config",
         |      "namespace": "test",
         |      "selfLink": "/api/v1/namespaces/test/configmaps/test-config",
         |      "uid": "942fd048-996b-11e7-bfc0-42010af00004",
         |      "resourceVersion": "61623643",
         |      "creationTimestamp": "2017-09-14T16:41:38Z"
         |    },
         |    "data": {
         |      "test.dtab": "/svc => /$/inet/127.1/11111;\n"
         |    }
         |  }
         |}""".stripMargin
    )

    val DtabModified = Buf.Utf8(
      """|{
         |  "type": "MODIFIED",
         |  "object": {
         |    "kind": "ConfigMap",
         |    "apiVersion": "v1",
         |    "metadata": {
         |      "name": "test-config",
         |      "namespace": "test",
         |      "selfLink": "/api/v1/namespaces/test/configmaps/test-config",
         |      "uid": "942fd048-996b-11e7-bfc0-42010af00004",
         |      "resourceVersion": "61623848",
         |      "creationTimestamp": "2017-09-14T16:41:38Z"
         |    },
         |    "data": {
         |      "test.dtab": "/ph => /$/io.buoyant.rinet ;\n/svc => /ph/80 ;\n/svc => /$/io.buoyant.porthostPfx/ph\n"
         |    }
         |  }
         |}""".stripMargin
    )

    val DtabModifiedBad = Buf.Utf8(
      """|{
         |  "type": "MODIFIED",
         |  "object": {
         |    "kind": "ConfigMap",
         |    "apiVersion": "v1",
         |    "metadata": {
         |      "name": "test-config",
         |      "namespace": "test",
         |      "selfLink": "/api/v1/namespaces/test/configmaps/test-config",
         |      "uid": "942fd048-996b-11e7-bfc0-42010af00004",
         |      "resourceVersion": "61623938",
         |      "creationTimestamp": "2017-09-14T16:41:38Z"
         |    },
         |    "data": {
         |      "test.dtab": "blargh im not a dtab!!!\n"
         |    }
         |  }
         |}""".stripMargin
    )
  }

  trait Fixtures {
    @volatile var writer: Writer = null
    val service = Service.mk[Request, Response] {
      case req if req.uri.startsWith("/api/v1/namespaces/test/configmaps/test-config") =>
        val rsp = Response()
        rsp.content = Rsps.Get
        Future.value(rsp)
      case req if req.uri.startsWith("/api/v1/watch/namespaces/test/configmaps/test-config") =>
        val rsp = Response()
        rsp.setChunked(true)
        writer = rsp.writer
        Future.value(rsp)
      case req =>
        throw new TestFailedException(s"Unexpected request $req", 1)
    }

    object TestConfigMapInterpreterConfig extends {
      override val api = v1.Api(service).withNamespace("test")
    } with ConfigMapInterpreterConfig(
      None, None, Some("test"), "test-config", "test.dtab"
    )

    val interpreter = TestConfigMapInterpreterConfig.newInterpreter(Params.empty)

    val activity = interpreter.asInstanceOf[ConfiguredDtabNamer].dtab

    @volatile var state: Activity.State[Dtab] = Activity.Pending

    activity.states.respond { s =>
      log.info("%s", s)
      state = s
    }
  }

  test("interpreter registration") {
    assert(LoadService[InterpreterInitializer]()
      .exists(_.isInstanceOf[ConfigMapInterpreterInitializer]))
  }

  private[this] def parse(yaml: String): ConfigMapInterpreterConfig =
    Parser.objectMapper(yaml, Iterable(Seq(ConfigMapInterpreterInitializer)))
      .readValue[InterpreterConfig](yaml)
      .asInstanceOf[ConfigMapInterpreterConfig]

  test("parse config") {
    val yaml =
      s"""|kind: io.l5d.k8s.configMap
          |host: "foo"
          |port: 8888
          |namespace: "my-great-namespace"
          |name: "configMap"
          |filename: "test.dtab"
          |""".stripMargin
    val config = parse(yaml)
    inside(config) {
      case ConfigMapInterpreterConfig(host, port, namespace, name, filename) =>
        assert(host.contains("foo"))
        assert(port.contains(Port(8888)))
        assert(namespace.contains("my-great-namespace"))
        assert(name == "configMap")
        assert(filename == "test.dtab")
    }
  }

  test("get empty dtab") {
    val yaml =
      s"""|kind: io.l5d.k8s.configMap
          |name: "configMap"
          |filename: "test.dtab"
          |""".stripMargin
    val config = parse(yaml)
    val configMap = ConfigMap(Map[String, String]())
    assert(config.getDtab(configMap).asScala.success.value.isEmpty)
  }

  test("get non-empty dtab") {
    val yaml =
      s"""|kind: io.l5d.k8s.configMap
          |name: "configMap"
          |filename: "test.dtab"
          |""".stripMargin
    val config = parse(yaml)
    val dtab = "/foo => /bar/baz;"
    val configMap = ConfigMap(Map(
      "test.dtab" -> dtab,
      "otherTest.dtab" -> "quux => quuux"
    ))
    assert(config.getDtab(configMap) == Return(Dtab.read(dtab)))
  }

  test("modifying dtab in ConfigMap changes interpreter's dtab") {
    val _ = new Fixtures {
      await(activity.toFuture)
      await(writer.write(Rsps.Added))
      await(activity.toFuture)
      assert(state.toString() == "Ok(Dtab(/svc=>/$/inet/127.1/11111))")

      await(writer.write(Rsps.DtabModified))
      await(activity.toFuture)
      assert(state.toString() == "Ok(Dtab(/ph=>/$/io.buoyant.rinet;/svc=>/ph/80;/svc=>/$/io.buoyant.porthostPfx/ph))")
    }
  }

  test("modifying unrelated entry in ConfigMap doesn't change interpreter's dtab") {
    val _ = new Fixtures {
      await(activity.toFuture)
      await(writer.write(Rsps.Added))
      await(activity.toFuture)
      assert(state.toString() == "Ok(Dtab(/svc=>/$/inet/127.1/11111))")
      await(writer.write(Rsps.UnrelatedAdded))
      await(activity.toFuture)
      assert(state.toString() == "Ok(Dtab(/svc=>/$/inet/127.1/11111))")
      await(writer.write(Rsps.UnrelatedRemoved))
      await(activity.toFuture)
      assert(state.toString() == "Ok(Dtab(/svc=>/$/inet/127.1/11111))")
    }
  }

  test("writing a bad dtab causes interpreter to fall back to last dtab") {
    val _ = new Fixtures {
      await(activity.toFuture)
      await(writer.write(Rsps.Added))
      await(activity.toFuture)
      assert(state.toString() == "Ok(Dtab(/svc=>/$/inet/127.1/11111))")
      await(writer.write(Rsps.DtabModifiedBad))
      await(activity.toFuture)
      assert(state.toString() == "Ok(Dtab(/svc=>/$/inet/127.1/11111))")
    }
  }

  test("dtab can still be updated after receiving a bad dtab update") {
    // reproduction for issue where invalid Dtab updates prevented
    // the interpreter from receiving future good updates (linkerd#1639)
    val _ = new Fixtures {
      await(activity.toFuture)
      await(writer.write(Rsps.Added))
      await(activity.toFuture)
      assert(state.toString() == "Ok(Dtab(/svc=>/$/inet/127.1/11111))")
      await(writer.write(Rsps.DtabModifiedBad))
      await(activity.toFuture)
      assert(state.toString() == "Ok(Dtab(/svc=>/$/inet/127.1/11111))")
      await(writer.write(Rsps.DtabModified))
      await(activity.toFuture)
      assert(state.toString() == "Ok(Dtab(/ph=>/$/io.buoyant.rinet;/svc=>/ph/80;/svc=>/$/io.buoyant.porthostPfx/ph))")
    }
  }

}
