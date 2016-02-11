import sbt._
import sbt.Keys._
import sbtassembly.AssemblyKeys._
import sbtdocker.DockerKeys._
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

  val marathon = projectDir("marathon")
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
        .withLib(Deps.finagle("serversets").exclude("org.slf4j", "slf4j-jdk14"))
        .withTests()
        .dependsOn(core % "compile->compile;test->test")

      val all = projectDir("linkerd/namer")
        .aggregate(fs, k8s, consul, serversets)
    }

    object Protocol {
      val http = projectDir("linkerd/protocol/http")
        .withTests().withE2e().withIntegration()
        .dependsOn(
          core % "compile->compile;e2e->test;integration->test",
          tls % "test",
          Router.http)

      val mux = projectDir("linkerd/protocol/mux")
        .dependsOn(core, Router.mux)

      val thrift = projectDir("linkerd/protocol/thrift")
        .dependsOn(core, Router.thrift)

      val all = projectDir("linkerd/protocol")
        .aggregate(http, mux, thrift)
    }

    val tls = projectDir("linkerd/tls")
      .dependsOn(core)

    val all = projectDir("linkerd")
      .aggregate(admin, core, main, Namer.all, Protocol.all, tls)
      .configs(Minimal, Bundle)
      // Minimal cofiguration includes a runtime, HTTP routing and the
      // fs service discovery.
      .configDependsOn(Minimal)(admin, core, main, Namer.fs, Protocol.http)
      .settings(inConfig(Minimal)(MinimalSettings))
      .withLib(Deps.finagle("stats") % Minimal)
      // Bundle is includes all of the supported features:
      .configDependsOn(Bundle)(
        Namer.consul, Namer.k8s, Namer.serversets,
        Protocol.mux, Protocol.thrift)
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
  val linkerdTls = Linkerd.tls
  val router = Router.all
  val routerCore = Router.core
  val routerHttp = Router.http
  val routerMux = Router.mux
  val routerThrift = Router.thrift
  val routerThriftIdl = Router.thriftIdl

  // Unified documentation via the sbt-unidoc plugin
  val all = project("all", file("."))
    .aggregate(k8s, consul, Linkerd.all, Router.all, testUtil)
    .settings(unidocSettings)
    .settings(
      assembly <<= assembly in linkerd,
      docker <<= docker in linkerd,
      dockerBuildAndPush <<= dockerBuildAndPush in linkerd,
      dockerPush <<= dockerPush in linkerd
    )
}
