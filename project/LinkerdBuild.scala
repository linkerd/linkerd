import sbt._
import sbt.Keys._
import sbtassembly.AssemblyKeys._
import sbtdocker.DockerKeys._
import sbtunidoc.Plugin._
import pl.project13.scala.sbt.JmhPlugin

/**
 * Project layout.
 *
 * - consul/ -- consul client
 * - k8s/ -- finagle kubernetes client
 * - marathon/ -- marathon client
 * - router/ -- finagle router libraries
 * - namer/ -- name resolution
 * - config/ -- configuration utilities
 * - linkerd/ -- runtime, and modules
 * - test-util/ -- async test helpers; provided by [[Base]]
 */
object LinkerdBuild extends Base {

  val k8s = projectDir("k8s")
    .withTwitterLib(Deps.finagle("http"))
    .withLibs(Deps.jackson)
    .withTests()

  val consul = projectDir("consul")
    .withTwitterLib(Deps.finagle("http"))
    .withLibs(Deps.jackson)
    .withTests()

  val marathon = projectDir("marathon")
    .withTwitterLib(Deps.finagle("http"))
    .withLibs(Deps.jackson)
    .withTests()

  object Router {
    val core = projectDir("router/core")
      .withTwitterLib(Deps.finagle("core"))
      .withTests()
      .withE2e()

    val http = projectDir("router/http")
      .dependsOn(core)
      .withTwitterLib(Deps.finagle("http"))
      .withTests()
      .withE2e()

    val mux = projectDir("router/mux")
      .dependsOn(core)
      .withTwitterLib(Deps.finagle("mux"))
      .withE2e()

    val thriftIdl = projectDir("router/thrift-idl")
      .withTwitterLib(Deps.finagle("thrift"))

    val thrift = projectDir("router/thrift")
      .withTwitterLib(Deps.finagle("thrift"))
      .withTests()
      .withE2e()
      .dependsOn(
        core,
        thriftIdl % "test,e2e"
      )

    val all = projectDir("router")
      .aggregate(core, http, mux, thrift)
  }

  val configCore = projectDir("config")
    .withTwitterLibs(Deps.finagle("core"), Deps.finagle("thrift"))
    .withLibs(Deps.jackson)
    .withLib(Deps.jacksonYaml)

  object Namer {
    val core = projectDir("namer/core")
      .dependsOn(configCore)
      .withLib(Deps.jacksonCore)
      .withTests()

    val consul = projectDir("namer/consul")
      .dependsOn(LinkerdBuild.consul, core)
      .withTests()

    val fs = projectDir("namer/fs")
      .dependsOn(core)
      .withTests()

    val k8s = projectDir("namer/k8s")
      .dependsOn(LinkerdBuild.k8s, core)
      .withTests()

    val marathon = projectDir("namer/marathon")
      .dependsOn(LinkerdBuild.marathon, core)
      .dependsOn(core)
      .withTests()

    val serversets = projectDir("namer/serversets")
      .withTwitterLib(Deps.finagle("serversets").exclude("org.slf4j", "slf4j-jdk14"))
      .withTests()
      .dependsOn(core % "compile->compile;test->test")

    val all = projectDir("namer")
      .aggregate(core, consul, fs, k8s, marathon, serversets)
  }

  val admin = projectDir("admin")
    .dependsOn(configCore)
    .withTwitterLib(Deps.twitterServer)

  object Linkerd {

    val core = projectDir("linkerd/core")
      .dependsOn(
        Router.core,
        configCore,
        LinkerdBuild.admin,
        Namer.core % "compile->compile;test->test"
      )
      .withLib(Deps.jacksonCore)
      .withTests()
      .configWithLibs(Test)(Deps.jacksonDatabind, Deps.jacksonYaml)
      .withBuildProperties()

    val tls = projectDir("linkerd/tls")
      .dependsOn(core)
      .withTests()


    object Identifier {
      val http = projectDir("linkerd/identifier/http")
        .dependsOn(core, Router.http)
        .withTests()

      val all = projectDir("linkerd/identifier")
        .aggregate(http)
    }

    object Protocol {
      val http = projectDir("linkerd/protocol/http")
        .withTests().withE2e().withIntegration()
        .dependsOn(
          core % "compile->compile;e2e->test;integration->test",
          tls % "integration",
          Namer.fs % "integration",
          Identifier.http,
          Router.http)

      val mux = projectDir("linkerd/protocol/mux")
        .dependsOn(core, Router.mux)

      val thrift = projectDir("linkerd/protocol/thrift")
        .dependsOn(core, Router.thrift)
        .withTests()

      val all = projectDir("linkerd/protocol")
        .aggregate(http, mux, thrift)

      val benchmark = projectDir("linkerd/protocol/benchmark")
        .dependsOn(http, Protocol.http)
        .dependsOn(testUtil)
        .withTwitterLib(Deps.twitterUtil("benchmark"))
        .enablePlugins(JmhPlugin)
    }

    val admin = projectDir("linkerd/admin")
      .withTwitterLib(Deps.twitterServer)
      .withTests()
      .dependsOn(core % "compile->compile;test->test")
      .dependsOn(LinkerdBuild.admin)
      .dependsOn(Namer.core)
      .dependsOn(Protocol.thrift % "test")

    val main = projectDir("linkerd/main")
      .dependsOn(admin, configCore, core, LinkerdBuild.admin)
      .withTwitterLib(Deps.twitterServer)
      .withLibs(Deps.jacksonCore, Deps.jacksonDatabind, Deps.jacksonYaml)
      .withBuildProperties()

    val all = projectDir("linkerd")
      .aggregate(admin, core, main, configCore, Identifier.all, Namer.all, Protocol.all, tls)
      .configs(Minimal, Bundle)
      // Minimal cofiguration includes a runtime, HTTP routing and the
      // fs service discovery.
      .configDependsOn(Minimal)(admin, core, main, configCore, Namer.fs, Protocol.http)
      .settings(inConfig(Minimal)(MinimalSettings))
      .withTwitterLib(Deps.finagle("stats") % Minimal)
      // Bundle is includes all of the supported features:
      .configDependsOn(Bundle)(
        Identifier.http,
        Namer.consul, Namer.k8s, Namer.marathon, Namer.serversets,
        Protocol.mux, Protocol.thrift,
        tls)
      .settings(inConfig(Bundle)(BundleSettings))
      .settings(
        assembly <<= assembly in Bundle,
        docker <<= docker in Bundle,
        dockerBuildAndPush <<= dockerBuildAndPush in Bundle,
        dockerPush <<= dockerPush in Bundle
      )
  }

  /*
   * linkerd packaging configurations.
   *
   * linkerd is configured to be assembled into an executable and may
   * be assembled into a dockerfile.
   */

  /**
   * An assembly-running script that adds the linkerd plugin directory
   * to the classpath if it exists.
   */
  val linkerdExecScript =
    """|#!/bin/sh
       |
       |jars="$0"
       |if [ -n "$L5D_HOME" ] && [ -d $L5D_HOME/plugins ]; then
       |  for jar in $L5D_HOME/plugins/*.jar ; do
       |    jars="$jars:$jar"
       |  done
       |fi
       |exec ${JAVA_HOME:-/usr}/bin/java -XX:+PrintCommandLineFlags \
       |     $JVM_OPTIONS -cp $jars -server io.buoyant.Linkerd "$@"
       |""".stripMargin.split("\n").toSeq

  val Minimal = config("minimal")
  val MinimalSettings = Defaults.configSettings ++ appPackagingSettings ++ Seq(
    mainClass := Some("io.buoyant.Linkerd"),
    assemblyExecScript := linkerdExecScript,
    dockerEnvPrefix := "L5D_"
  )

  val Bundle = config("bundle") extend Minimal
  val BundleSettings = MinimalSettings ++ Seq(
    assemblyJarName in assembly := s"${name.value}-${version.value}-exec",
    imageName in docker := (imageName in docker).value.copy(tag = Some(version.value))
  )

  // Find example configurations by searching the examples directory for config files.
  val ConfigFileRE = """^(.*)\.l5d$""".r
  val exampleConfigs = file("examples").list().toSeq.collect {
    case ConfigFileRE(name) => config(name) -> exampleConfig(name)
  }
  def exampleConfig(name:  String): Configuration = name match {
    case "http" => Minimal
    case _ => Bundle
  }

  val examples = projectDir("examples")
    .withExamples(Linkerd.all, exampleConfigs)

  // All projects must be exposed at the root of the object:

  val linkerd = Linkerd.all
  val linkerdAdmin = Linkerd.admin
  val linkerdConfig = configCore
  val linkerdCore = Linkerd.core
  val linkerdIdentifier = Linkerd.Identifier.all
  val linkerdIdentifierHttp = Linkerd.Identifier.http
  val linkerdMain = Linkerd.main
  val namer = Namer.all
  val namerCore = Namer.core
  val namerConsul = Namer.consul
  val namerFs = Namer.fs
  val namerK8s = Namer.k8s
  val namerMarathon = Namer.marathon
  val namerServersets = Namer.serversets
  val linkerdProtocol = Linkerd.Protocol.all
  val linkerdBenchmark = Linkerd.Protocol.benchmark
  val linkerdProtocolHttp = Linkerd.Protocol.http
  val linkerdProtocolMux = Linkerd.Protocol.mux
  val linkerdProtocolThrift = Linkerd.Protocol.thrift
  val linkerdTls = Linkerd.tls
  val router = Router.all
  val routerCore = Router.core
  val routerHttp = Router.http
  val routerMux = Router.mux
  val routerThrift = Router.thrift
  val routerThriftIdl = Router.thriftIdl

  // Unified documentation via the sbt-unidoc plugin
  val all = project("all", file("."))
    .aggregate(k8s, consul, marathon, Linkerd.all, Router.all, Namer.all, configCore, admin, testUtil)
    .settings(unidocSettings)
    .settings(
      assembly <<= assembly in linkerd,
      docker <<= docker in linkerd,
      dockerBuildAndPush <<= dockerBuildAndPush in linkerd,
      dockerPush <<= dockerPush in linkerd
    )
}
