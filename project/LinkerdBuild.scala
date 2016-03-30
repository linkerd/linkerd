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
 * - linkerd/ -- linkerd runtime and modules
 * - namerd/ -- namerd runtime and modules
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
      .dependsOn(core % "compile->compile;test->test")
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

  object Interpreter {

    Namerd.hashCode

    val namerd = projectDir("interpreter/namerd")
      .dependsOn(Namerd.Iface.interpreterThrift, Linkerd.core)
      .withTests()

  }

  val admin = projectDir("admin")
    .dependsOn(configCore, Namer.core)
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
        Namer.consul, Namer.k8s, Namer.marathon, Namer.serversets, Interpreter.namerd,
        Protocol.mux, Protocol.thrift,
        tls)
      .settings(inConfig(Bundle)(BundleSettings))
      .settings(
        assembly <<= assembly in Bundle,
        docker <<= docker in Bundle,
        dockerBuildAndPush <<= dockerBuildAndPush in Bundle,
        dockerPush <<= dockerPush in Bundle
      )

    // Find example configurations by searching the examples directory for config files.
    val ConfigFileRE = """^(.*)\.l5d$""".r
    val exampleConfigs = file("linkerd/examples").list().toSeq.collect {
      case ConfigFileRE(name) => config(name) -> exampleConfig(name)
    }
    def exampleConfig(name:  String): Configuration = name match {
      case "http" => Minimal
      case _ => Bundle
    }

    val examples = projectDir("linkerd/examples")
      .withExamples(Linkerd.all, exampleConfigs)
  }

  object Namerd {

    val core = projectDir("namerd/core")
      .dependsOn(
        Namer.core,
        configCore,
        admin,
        Namer.fs % "test"
      )
      .withTests()

    object Storage {

      val inMemory = projectDir("namerd/storage/in-memory")
        .dependsOn(core % "test->test;compile->compile")
        .withTests()

      val zk = projectDir("namerd/storage/zk")
        .dependsOn(core)
        .withTwitterLib(Deps.finagle("serversets").exclude("org.slf4j", "slf4j-jdk14"))
        .withTests()

      val all = projectDir("namerd/storage")
        .aggregate(inMemory, zk)
    }

    object Iface {

      val controlHttp = projectDir("namerd/iface/control-http")
        .dependsOn(core, Storage.inMemory % "test")
        .withTwitterLib(Deps.finagle("http"))
        .withTests()

      val interpreterThriftIdl = projectDir("namerd/iface/interpreter-thrift-idl")
        .withTwitterLib(Deps.finagle("thrift"))

      val interpreterThrift = projectDir("namerd/iface/interpreter-thrift")
        .dependsOn(core, interpreterThriftIdl)
        .withTwitterLibs(Deps.finagle("thrift"), Deps.finagle("thriftmux"))
        .withTests()

      val all = projectDir("namerd/iface")
        .aggregate(controlHttp, interpreterThrift)

    }

    val main = projectDir("namerd/main")
      .dependsOn(core, admin, configCore)
      .withBuildProperties()

    val Minimal = config("minimal")
    val MinimalSettings = Defaults.configSettings ++ appPackagingSettings ++ Seq(
      mainClass := Some("io.buoyant.namerd.Main"),
      dockerEnvPrefix := "N4D"
    )

    val Bundle = config("bundle") extend Minimal
    val BundleSettings = MinimalSettings ++ Seq(
      assemblyJarName in assembly := s"${name.value}-${version.value}-exec",
      imageName in docker := (imageName in docker).value.copy(tag = Some(version.value))
    )

    val all = projectDir("namerd")
      .aggregate(core, Storage.all, Iface.all, main, Router.http)
      .configs(Minimal, Bundle)
      // Minimal cofiguration includes a runtime, HTTP routing and the
      // fs service discovery.
      .configDependsOn(Minimal)(
        core, main, Namer.fs, Storage.inMemory, Router.http,
        Iface.controlHttp, Iface.interpreterThrift
      )
      .settings(inConfig(Minimal)(MinimalSettings))
      .withTwitterLib(Deps.finagle("stats") % Minimal)
      // Bundle is includes all of the supported features:
      .configDependsOn(Bundle)(
        Namer.consul, Namer.k8s, Namer.marathon, Namer.serversets, Storage.zk
      )
      .settings(inConfig(Bundle)(BundleSettings))
      .settings(
        assembly <<= assembly in Bundle,
        docker <<= docker in Bundle,
        dockerBuildAndPush <<= dockerBuildAndPush in Bundle,
        dockerPush <<= dockerPush in Bundle
      )

    // Find example configurations by searching the examples directory for config files.
    val ConfigFileRE = """^(.*)\.yaml$""".r
    val exampleConfigs = file("namerd/examples").list().toSeq.collect {
      case ConfigFileRE(name) => config(name) -> exampleConfig(name)
    }
    def exampleConfig(name:  String): Configuration = name match {
      case "basic" => Minimal
      case _ => Bundle
    }

    val examples = projectDir("namerd/examples")
      .withExamples(Namerd.all, exampleConfigs)
  }

  val validator = projectDir("validator")
    .withTwitterLibs(Deps.twitterServer, Deps.twitterUtil("events"), Deps.finagle("http"))
    .settings(mainClass := Some("io.buoyant.namerd.Validator"))

  // All projects must be exposed at the root of the object:

  val linkerd = Linkerd.all
  val linkerdExamples = Linkerd.examples
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
  val namerd = Namerd.all
  val namerdExamples = Namerd.examples
  val namerdCore = Namerd.core
  val namerdStorageInMemory = Namerd.Storage.inMemory
  val namerdStorageZk = Namerd.Storage.zk
  val namerdStorage = Namerd.Storage.all
  val namerdIfaceControlHttp = Namerd.Iface.controlHttp
  val namerdIfaceInterpreterThriftIdl = Namerd.Iface.interpreterThriftIdl
  val namerdIfaceInterpreterThrift = Namerd.Iface.interpreterThrift
  val namerdIface = Namerd.Iface.all
  val namerdMain = Namerd.main
  val interpreterNamerd = Interpreter.namerd

  // Unified documentation via the sbt-unidoc plugin
  val all = project("all", file("."))
    .aggregate(k8s, consul, marathon, Linkerd.all, Namerd.all, Router.all, Namer.all, configCore, admin, testUtil, interpreterNamerd)
    .settings(unidocSettings)
    .settings(
      assembly <<= assembly in linkerd,
      docker <<= docker in linkerd,
      dockerBuildAndPush <<= dockerBuildAndPush in linkerd,
      dockerPush <<= dockerPush in linkerd
    )
}
