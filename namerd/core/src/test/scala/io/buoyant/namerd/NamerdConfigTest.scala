package io.buoyant.namerd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.JsonMappingException
import com.twitter.finagle.{Path, Dtab}
import com.twitter.io.Buf
import com.twitter.util.{Activity, Future}
import io.buoyant.namer.fs.FsInitializer
import org.scalatest.FunSuite

object TestDtabStoreInitializer extends DtabStoreInitializer {
  override def configClass = classOf[TestDtabStore]
}

class TestDtabStore extends DtabStoreConfig {
  @JsonIgnore
  override def mkDtabStore = NullDtabStore
}

class NamerdConfigTest extends FunSuite {
  val initializers = NamerdConfig.Initializers(Seq(FsInitializer), Seq(TestDtabStoreInitializer), Seq(TestNamerInterfaceInitializer))

  test("parse namerd config") {
    val yaml = """
      |storage:
      |  kind: io.buoyant.namerd.TestDtabStore
      |namers:
      |- kind: io.l5d.fs
      |  rootDir: .
      |interfaces:
      |- kind: test
      |  ip: 127.0.0.1
      |  port: 1
    """.stripMargin

    val config = NamerdConfig.loadNamerd(yaml, initializers)
    assert(config.namers.get.head.prefix == Path.read("/#/io.l5d.fs"))
    assert(config.interfaces.head.addr.getAddress.isLoopbackAddress)
    assert(config.interfaces.head.addr.getPort == 1)
    // just check that this don't blow up
    val _ = config.mk()
  }

  test("parse minimal namerd config") {
    val yaml = """
      |storage:
      |  kind: io.buoyant.namerd.TestDtabStore
      |interfaces:
      |- kind: test
      |  ip: 127.0.0.1
      |  port: 1
    """.stripMargin

    val config = NamerdConfig.loadNamerd(yaml, initializers)
    assert(config.namers == None)
    assert(config.interfaces.head.addr.getAddress.isLoopbackAddress)
    assert(config.interfaces.head.addr.getPort == 1)
    // just check that this don't blow up
    val _ = config.mk()
  }

  test("missing interfaces validation") {
    val missingInterfaces =
      """
        |storage:
        |  kind: io.buoyant.namerd.TestDtabStore
        |namers:
        |- kind: io.l5d.fs
        |  rootDir: .
      """.stripMargin

    val interfaceEx = intercept[JsonMappingException] {
      NamerdConfig.loadNamerd(missingInterfaces, initializers)
    }
    assert(interfaceEx.getMessage.contains("'interfaces' field is required"))
  }

  test("empty interfaces validation") {
    val emptyInterfaces =
      """
        |storage:
        |  kind: io.buoyant.namerd.TestDtabStore
        |namers:
        |- kind: io.l5d.fs
        |  rootDir: .
        |interfaces: []
      """.stripMargin

    val interfaceEx = intercept[JsonMappingException] {
      NamerdConfig.loadNamerd(emptyInterfaces, initializers)
    }
    assert(interfaceEx.getMessage.contains("One or more interfaces must be specified"))
  }
}
