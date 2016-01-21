import sbt._
import sbt.Keys._
import sbtunidoc.Plugin._
import scala.language.implicitConversions

/**
 * Project layout.
 *
 * - k8s/ -- finagle kubernetes client
 * - router/ -- finagle router libraries
 * - linkerd/ -- configuration, runtime, and modules
 * - test-util/ -- async test helpers; provided by [[Base]]
 */
object LinkerdBuild extends Base {

  val k8s = projectDir("k8s")
    .withLib(Deps.finagle("http"))
    .withLibs(Deps.jackson)
    .withTests()

  object Router {
    val core = projectDir("router/core")
      .withLib(Deps.finagle("core"))
      .withTests()
      .withE2e()

    val http = projectDir("router/http")
      .dependsOn(core)
      .withLib(Deps.finagle("http"))
      .withTests()
      .withE2e()

    val mux = projectDir("router/mux")
      .dependsOn(core)
      .withLib(Deps.finagle("mux"))
      .withE2e()

    val thriftIdl = projectDir("router/thrift-idl")
      .withLib(Deps.finagle("thrift"))

    val thrift = projectDir("router/thrift")
      .withLib(Deps.finagle("thrift"))
      .withTests()
      .withE2e()
      .dependsOn(core, thriftIdl % "e2e,test")

    val all = projectDir("router")
      .aggregate(core, http, mux, thrift)
  }

  object Linkerd {
    val core = projectDir("linkerd/core")
      .dependsOn(Router.core)
      .withLib(Deps.jacksonCore)
      .withTests().withTestLibs(Deps.jacksonDatabind, Deps.jacksonYaml)
      .withBuildProperties()

    val admin = projectDir("linkerd/admin")
      .withLib(Deps.twitterServer)
      .withTests()
      .dependsOn(core % "compile->compile;test->test")

    val main = projectDir("linkerd/main")
      .dependsOn(admin, core)
      .withLib(Deps.twitterServer)
      .withLibs(Deps.jacksonCore, Deps.jacksonDatabind, Deps.jacksonYaml)
      .withBuildProperties()

    object Namer {
      val fs = projectDir("linkerd/namer/fs")
        .dependsOn(core)

      val k8s = projectDir("linkerd/namer/k8s")
        .dependsOn(LinkerdBuild.k8s, core)
        .withTests()

      val all = projectDir("linkerd/namer")
        .aggregate(fs, k8s)
    }

    object Protocol {
      val http = projectDir("linkerd/protocol/http")
        .dependsOn(core, Router.http)
        .withTests()

      val mux = projectDir("linkerd/protocol/mux")
        .dependsOn(core, Router.mux)

      val thrift = projectDir("linkerd/protocol/thrift")
        .dependsOn(core, Router.thrift)

      val all = projectDir("linkerd/protocol")
        .aggregate(http, mux, thrift)
    }

    val all = projectDir("linkerd")
      .aggregate(admin, core, main, Namer.all, Protocol.all)
  }

  val linkerd = Linkerd.all
  val linkerdAdmin = Linkerd.admin
  val linkerdCore = Linkerd.core
  val linkerdMain = Linkerd.main
  val linkerdNamer = Linkerd.Namer.all
  val linkerdNamerFs = Linkerd.Namer.fs
  val linkerdNamerK8s = Linkerd.Namer.k8s
  val linkerdProtocol = Linkerd.Protocol.all
  val linkerdProtocolHttp = Linkerd.Protocol.http
  val linkerdProtocolMux = Linkerd.Protocol.mux
  val linkerdProtocolThrift = Linkerd.Protocol.thrift
  val router = Router.all
  val routerCore = Router.core
  val routerHttp = Router.http
  val routerMux = Router.mux
  val routerThrift = Router.thrift
  val routerThriftIdl = Router.thriftIdl

  // Unified documentation via the sbt-unidoc plugin
  val all = Project("all", file("."))
    .aggregate(k8s, Linkerd.all, Router.all, testUtil)
    .settings(unidocSettings)
}
