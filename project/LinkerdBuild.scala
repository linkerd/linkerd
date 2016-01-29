import sbt._
import sbt.Keys._
import Keys._
import sbtassembly.AssemblyKeys._
import sbtassembly.AssemblyPlugin.assemblySettings
import sbtunidoc.Plugin._

/**
 * Project layout.
 *
 * - consul/ -- consul client
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

  val consul = projectDir("consul")
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
      .dependsOn(
        core,
        thriftIdl % "test,e2e"
      )

    val all = projectDir("router")
      .aggregate(core, http, mux, thrift)
  }

  object Linkerd {
    val core = projectDir("linkerd/core")
      .dependsOn(Router.core)
      .withLib(Deps.jacksonCore)
      .withTests()
      .configWithLibs(Test)(Deps.jacksonDatabind, Deps.jacksonYaml)
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
      val consul = projectDir("linkerd/namer/consul")
        .dependsOn(LinkerdBuild.consul, core)
        .withTests()

      val fs = projectDir("linkerd/namer/fs")
        .dependsOn(core)

      val k8s = projectDir("linkerd/namer/k8s")
        .dependsOn(LinkerdBuild.k8s, core)
        .withTests()

      val serversets = projectDir("linkerd/namer/serversets")
        .withLib(Deps.finagle("serversets"))
        .withTests()
        .dependsOn(core % "compile->compile;test->test")

      val all = projectDir("linkerd/namer")
        .aggregate(fs, k8s, consul, serversets)
    }

    object Protocol {
      val http = projectDir("linkerd/protocol/http")
        .withTests()
        .withIntegration()
        .dependsOn(core % "compile->compile;integration->test", Router.http)

      val mux = projectDir("linkerd/protocol/mux")
        .dependsOn(core, Router.mux)

      val thrift = projectDir("linkerd/protocol/thrift")
        .dependsOn(core, Router.thrift)

      val all = projectDir("linkerd/protocol")
        .aggregate(http, mux, thrift)
    }

    val all = projectDir("linkerd")
      .aggregate(admin, core, main, Namer.all, Protocol.all)
      .configs(Minimal, Bundle)
      // Minimal cofiguration includes a runtime, HTTP routing and the
      // fs service discovery.
      .configDependsOn(Minimal)(admin, core, main, Namer.fs, Protocol.http)
      .settings(inConfig(Minimal)(MinimalSettings ++ assemblySettings))
      .withLib(Deps.finagle("stats") % Minimal)
      // Bundle is includes all of the supported features:
      .configDependsOn(Bundle)(Namer.consul, Namer.k8s, Namer.serversets, Protocol.mux, Protocol.thrift)
      .settings(inConfig(Bundle)(BundleSettings ++ assemblySettings))
      // top level settings -- make `assembly` do `bundle:assembly`
      .settings(assembly := (assembly in Bundle).value)
  }

  /*
   * linkerd packaging configurations.
   */

  val Minimal = config("minimal")
  val MinimalSettings = Defaults.configSettings ++ Seq(
    mainClass := Some("io.buoyant.Linkerd"),
    mainClass in assembly := mainClass.value,
    jarName in assembly := s"${name.value}-${configuration.value}-${version.value}.jar"
  )

  val Bundle = config("bundle") extend Minimal
  val BundleSettings = MinimalSettings ++ Seq(
    jarName in assembly := s"${name.value}-${version.value}.jar"
  )

  val ConfigFileRE = """^(.*)\.l5d$""".r
  val exampleConfigs = file("examples").list().toSeq.collect {
    case ConfigFileRE(name) =>
      val runtime = name match {
        case "http" => Minimal
        case name => Bundle
      }
      config(name) -> runtime
  }

  val examples = projectDir("examples")
    .withExamples(Linkerd.all, exampleConfigs)

  // All projects must be exposed at the root of the object:

  val linkerd = Linkerd.all
  val linkerdAdmin = Linkerd.admin
  val linkerdCore = Linkerd.core
  val linkerdMain = Linkerd.main
  val linkerdNamer = Linkerd.Namer.all
  val linkerdNamerConsul = Linkerd.Namer.consul
  val linkerdNamerFs = Linkerd.Namer.fs
  val linkerdNamerK8s = Linkerd.Namer.k8s
  val linkerdNamerServersets = Linkerd.Namer.serversets
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
    .aggregate(k8s, consul, Linkerd.all, Router.all, testUtil)
    .settings(unidocSettings)
    .settings(
      aggregate in assembly := false,
      assembly := (assembly in (linkerd, Bundle)).value
    )
}
