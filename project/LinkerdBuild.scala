import sbt._
import sbt.Keys._
import sbtassembly.AssemblyKeys._
import sbtdocker.DockerKeys._
import sbtunidoc.Plugin._
import scoverage.ScoverageKeys._
import pl.project13.scala.sbt.JmhPlugin

object LinkerdBuild extends Base {

  val Minimal = config("minimal")
  val Bundle = config("bundle") extend Minimal
  val Dcos = config("dcos") extend Bundle

  val consul = projectDir("consul")
    .withTwitterLib(Deps.finagle("http"))
    .withLibs(Deps.jackson)
    .withTests()

  val etcd = projectDir("etcd")
    .withTwitterLib(Deps.finagle("http"))
    .withLibs(Deps.jackson ++ Deps.jodaTime)
    .withTests().withIntegration()

  val k8s = projectDir("k8s")
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
      .settings(coverageExcludedPackages := ".*XXX_.*")

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
      .settings(coverageExcludedPackages := ".*thriftscala.*")

    val thrift = projectDir("router/thrift")
      .withTwitterLib(Deps.finagle("thrift"))
      .withTests()
      .withE2e()
      .dependsOn(
        core,
        thriftIdl % "test,e2e"
      )

    val all = projectDir("router")
      .settings(aggregateSettings)
      .aggregate(core, http, mux, thrift)
  }

  val configCore = projectDir("config")
    .withTwitterLibs(Deps.finagle("core"))
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
      .withTests()

    val serversets = projectDir("namer/serversets")
      .withTwitterLib(Deps.finagle("serversets").exclude("org.slf4j", "slf4j-jdk14"))
      .withTests()
      .dependsOn(core % "compile->compile;test->test")

    val zkLeader = projectDir("namer/zk-leader")
      .dependsOn(core)
      .withLib(Deps.zkCandidate)
      .withTests()

    val all = projectDir("namer")
      .settings(aggregateSettings)
      .aggregate(core, consul, fs, k8s, marathon, serversets, zkLeader)
  }

  val admin = projectDir("admin")
    .dependsOn(configCore, Namer.core)
    .withTwitterLib(Deps.twitterServer)
    .withTwitterLib(Deps.finagle("stats"))
    .withTests()

  val ConfigFileRE = """^(.*)\.yaml$""".r

  val execScriptJvmOptions =
    """|DEFAULT_JVM_OPTIONS="-Djava.net.preferIPv4Stack=true \
       |   -Dsun.net.inetaddr.ttl=60                         \
       |   -Xms${JVM_HEAP_MIN:-32M}                          \
       |   -Xmx${JVM_HEAP_MAX:-1024M}                        \
       |   -XX:+AggressiveOpts                               \
       |   -XX:+UseConcMarkSweepGC                           \
       |   -XX:+CMSParallelRemarkEnabled                     \
       |   -XX:+CMSClassUnloadingEnabled                     \
       |   -XX:+ScavengeBeforeFullGC                         \
       |   -XX:+CMSScavengeBeforeRemark                      \
       |   -XX:+UseCMSInitiatingOccupancyOnly                \
       |   -XX:CMSInitiatingOccupancyFraction=70             \
       |   -XX:-TieredCompilation                            \
       |   -XX:+UseStringDeduplication                       "
       |""".stripMargin

  object Namerd {

    val core = projectDir("namerd/core")
      .dependsOn(
        admin,
        configCore,
        Namer.core,
        Namer.fs % "test"
      )
      .withTests()

    object Storage {

      val inMemory = projectDir("namerd/storage/in-memory")
        .dependsOn(core % "test->test;compile->compile")
        .withTests()

      val etcd = projectDir("namerd/storage/etcd")
        .dependsOn(core, LinkerdBuild.etcd % "integration->integration;compile->compile")
        .withTests()
        .withIntegration()

      val zk = projectDir("namerd/storage/zk")
        .dependsOn(core)
        .withTwitterLib(Deps.finagle("serversets").exclude("org.slf4j", "slf4j-jdk14"))
        .withTests()

      val k8s = projectDir("namerd/storage/k8s")
        .dependsOn(core)
        .dependsOn(LinkerdBuild.k8s)
        .withTests()

      val all = projectDir("namerd/storage")
        .settings(aggregateSettings)
        .aggregate(inMemory, zk, k8s, etcd)
    }

    object Iface {

      val controlHttp = projectDir("namerd/iface/control-http")
        .withTwitterLib(Deps.finagle("http"))
        .withTests().dependsOn(
          core % "test->test;compile->compile",
          Storage.inMemory % "test"
        )

      val interpreterThriftIdl = projectDir("namerd/iface/interpreter-thrift-idl")
        .withTwitterLib(Deps.finagle("thrift"))
        .settings(coverageExcludedPackages := ".*thriftscala.*")

      val interpreterThrift = projectDir("namerd/iface/interpreter-thrift")
        .dependsOn(core)
        .dependsOn(interpreterThriftIdl)
        .withLib(Deps.guava)
        .withTwitterLibs(Deps.finagle("thrift"), Deps.finagle("thriftmux"))
        .withTests()

      val all = projectDir("namerd/iface")
        .settings(aggregateSettings)
        .aggregate(controlHttp, interpreterThriftIdl, interpreterThrift)
    }

    val main = projectDir("namerd/main")
      .dependsOn(core, admin, configCore)
      .withBuildProperties()
      .settings(coverageExcludedPackages := ".*")

    /**
     * An assembly-running script that adds the namerd plugin directory
     * to the classpath if it exists.
     */
    val execScript = (
      """|#!/bin/sh
         |
         |jars="$0"
         |if [ -n "$NAMERD_HOME" ] && [ -d $NAMERD_HOME/plugins ]; then
         |  for jar in $NAMERD_HOME/plugins/*.jar ; do
         |    jars="$jars:$jar"
         |  done
         |fi
         |""" +
      execScriptJvmOptions +
      """|exec ${JAVA_HOME:-/usr}/bin/java -XX:+PrintCommandLineFlags \
         |     ${JVM_OPTIONS:-$DEFAULT_JVM_OPTIONS} -cp $jars -server \
         |     io.buoyant.namerd.Main "$@"
         |"""
      ).stripMargin

    val Minimal = config("minimal")
    val MinimalSettings = Defaults.configSettings ++ appPackagingSettings ++ Seq(
      mainClass := Some("io.buoyant.namerd.Main"),
      assemblyExecScript := execScript.split("\n").toSeq,
      dockerEnvPrefix := "NAMERD_",
      unmanagedBase := baseDirectory.value / "plugins"
    )

    val Bundle = config("bundle") extend Minimal
    val BundleSettings = MinimalSettings ++ Seq(
      assemblyJarName in assembly := s"${name.value}-${version.value}-exec",
      dockerTag := version.value
    )

    /**
     * A DCOS-specific assembly-running script that:
     * 1) adds the namerd plugin directory to the classpath if it exists
     * 2) bootstraps zookeeper with a default path and dtabs
     * 3) boots namerd
     */
    val dcosExecScript = (
      """|#!/bin/sh
         |
         |jars="$0"
         |if [ -n "$NAMERD_HOME" ] && [ -d $NAMERD_HOME/plugins ]; then
         |  for jar in $NAMERD_HOME/plugins/*.jar ; do
         |    jars="$jars:$jar"
         |  done
         |fi
         |""" +
      execScriptJvmOptions +
      """|${JAVA_HOME:-/usr}/bin/java -XX:+PrintCommandLineFlags \
         |${JVM_OPTIONS:-$DEFAULT_JVM_OPTIONS} -cp $jars -server \
         |io.buoyant.namerd.DcosBootstrap "$@"
         |
         |${JAVA_HOME:-/usr}/bin/java -XX:+PrintCommandLineFlags \
         |${JVM_OPTIONS:-$DEFAULT_JVM_OPTIONS} -cp $jars -server \
         |io.buoyant.namerd.Main "$@"
         |
         |exit
         |"""
      ).stripMargin

    val dcosBootstrap = projectDir("namerd/dcos-bootstrap")
      .dependsOn(core, admin, configCore, Storage.zk)

    val DcosSettings = MinimalSettings ++ Seq(
      assemblyExecScript := dcosExecScript.split("\n").toSeq
    )

    val all = projectDir("namerd")
      .settings(aggregateSettings)
      .aggregate(core, dcosBootstrap, Storage.all, Iface.all, main)
      .configs(Minimal, Bundle, Dcos)
      // Minimal cofiguration includes a runtime, HTTP routing and the
      // fs service discovery.
      .configDependsOn(Minimal)(
        core, main, Namer.fs, Storage.inMemory, Router.http,
        Iface.controlHttp, Iface.interpreterThrift
      )
      .settings(inConfig(Minimal)(MinimalSettings))
      .withTwitterLib(Deps.finagle("stats") % Minimal)
      // Bundle includes all of the supported features:
      .configDependsOn(Bundle)(
        Namer.consul, Namer.k8s, Namer.marathon, Namer.serversets,
        Storage.etcd, Storage.inMemory, Storage.k8s, Storage.zk
      )
      .settings(inConfig(Bundle)(BundleSettings))
      .configDependsOn(Dcos)(dcosBootstrap)
      .settings(inConfig(Dcos)(DcosSettings))
      .settings(
        assembly <<= assembly in Bundle,
        docker <<= docker in Bundle,
        dockerBuildAndPush <<= dockerBuildAndPush in Bundle,
        dockerPush <<= dockerPush in Bundle
      )

    // Find example configurations by searching the examples directory for config files.
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

  object Interpreter {
    val namerd = projectDir("interpreter/namerd")
      .withTests()
      .dependsOn(Namer.core, Namerd.Iface.interpreterThrift)

    val all = projectDir("interpreter")
      .settings(aggregateSettings)
      .aggregate(namerd)
  }

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

    object Protocol {
      val http = projectDir("linkerd/protocol/http")
        .withTests().withE2e().withIntegration()
        .dependsOn(
          core % "compile->compile;e2e->test;integration->test",
          tls % "integration",
          Namer.fs % "integration",
          Router.http)

      val mux = projectDir("linkerd/protocol/mux")
        .dependsOn(core, Router.mux)

      val thrift = projectDir("linkerd/protocol/thrift")
        .dependsOn(core, Router.thrift)
        .withTests()

      val all = projectDir("linkerd/protocol")
        .settings(aggregateSettings)
        .aggregate(http, mux, thrift)

      val benchmark = projectDir("linkerd/protocol/benchmark")
        .dependsOn(http, Protocol.http)
        .withTests()
        .withTwitterLib(Deps.twitterUtil("benchmark"))
        .enablePlugins(JmhPlugin)
    }

    object Tracer {
      val zipkin = projectDir("linkerd/tracer/zipkin")
        .withTwitterLib(Deps.finagle("zipkin"))
        .dependsOn(core)
        .withTests()

      val all = projectDir("linkerd/tracer")
        .settings(aggregateSettings)
        .aggregate(zipkin)
    }

    val admin = projectDir("linkerd/admin")
      .withTwitterLib(Deps.twitterServer)
      .withTests()
      .dependsOn(core % "compile->compile;test->test")
      .dependsOn(LinkerdBuild.admin, Namer.core)
      .dependsOn(Protocol.thrift % "test")

    val main = projectDir("linkerd/main")
      .dependsOn(admin, configCore, core, LinkerdBuild.admin)
      .withTwitterLib(Deps.twitterServer)
      .withLibs(Deps.jacksonCore, Deps.jacksonDatabind, Deps.jacksonYaml)
      .withBuildProperties()
      .settings(coverageExcludedPackages := ".*")

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
    val execScript = (
      """|#!/bin/sh
         |
         |jars="$0"
         |if [ -n "$L5D_HOME" ] && [ -d $L5D_HOME/plugins ]; then
         |  for jar in $L5D_HOME/plugins/*.jar ; do
         |    jars="$jars:$jar"
         |  done
         |fi
         |""" +
      execScriptJvmOptions +
      """|exec ${JAVA_HOME:-/usr}/bin/java -XX:+PrintCommandLineFlags \
         |     ${JVM_OPTIONS:-$DEFAULT_JVM_OPTIONS} -cp $jars -server \
         |     io.buoyant.Linkerd "$@"
         |"""
      ).stripMargin

    val MinimalSettings = Defaults.configSettings ++ appPackagingSettings ++ Seq(
      mainClass := Some("io.buoyant.Linkerd"),
      assemblyExecScript := execScript.split("\n").toSeq,
      dockerEnvPrefix := "L5D_",
      unmanagedBase := baseDirectory.value / "plugins"
    )

    val BundleSettings = MinimalSettings ++ Seq(
      assemblyJarName in assembly := s"${name.value}-${version.value}-exec",
      dockerTag := version.value
    )

    val all = projectDir("linkerd")
      .settings(aggregateSettings)
      .aggregate(admin, core, main, configCore, Namer.all, Protocol.all, Tracer.all, tls)
      .configs(Minimal, Bundle)
      // Minimal cofiguration includes a runtime, HTTP routing and the
      // fs service discovery.
      .configDependsOn(Minimal)(admin, core, main, configCore, Namer.fs, Protocol.http)
      .settings(inConfig(Minimal)(MinimalSettings))
      .withTwitterLib(Deps.finagle("stats") % Minimal)
      // Bundle is includes all of the supported features:
      .configDependsOn(Bundle)(
        Namer.consul, Namer.k8s, Namer.marathon, Namer.serversets, Namer.zkLeader,
        Interpreter.namerd,
        Protocol.mux, Protocol.thrift,
        Tracer.zipkin,
        tls)
      .settings(inConfig(Bundle)(BundleSettings))
      .settings(
        assembly <<= assembly in Bundle,
        docker <<= docker in Bundle,
        dockerBuildAndPush <<= dockerBuildAndPush in Bundle,
        dockerPush <<= dockerPush in Bundle
      )

    // Find example configurations by searching the examples directory for config files.
    val exampleConfigs = file("linkerd/examples").list().toSeq.collect {
      case ConfigFileRE(name) => config(name) -> exampleConfig(name)
    }
    def exampleConfig(name: String): Configuration = name match {
      case "http" => Minimal
      case _ => Bundle
    }

    val examples = projectDir("linkerd/examples")
      .withExamples(Linkerd.all, exampleConfigs)
  }

  val validateAssembled = taskKey[Unit]("run validation against assembled artifacts")
  val validator = projectDir("validator")
    .withTwitterLibs(Deps.twitterServer, Deps.twitterUtil("events"), Deps.finagle("http"))
    .settings(
      mainClass := Some("io.buoyant.namerd.Validator"),
      validateAssembled := (Def.taskDyn {
        val linkerd = (assembly in Bundle in Linkerd.all).value
        val namerd = (assembly in Bundle in Namerd.all).value
        Def.task {
          (run in Compile).toTask(s" -linkerd.exec=$linkerd -namerd.exec=$namerd").value
        }
      }).value,
      coverageExcludedPackages := ".*"
    )

  // All projects must be exposed at the root of the object in
  // dependency-order:

  val router = Router.all
  val routerCore = Router.core
  val routerHttp = Router.http
  val routerMux = Router.mux
  val routerThrift = Router.thrift
  val routerThriftIdl = Router.thriftIdl

  val namer = Namer.all
  val namerCore = Namer.core
  val namerConsul = Namer.consul
  val namerFs = Namer.fs
  val namerK8s = Namer.k8s
  val namerMarathon = Namer.marathon
  val namerServersets = Namer.serversets
  val namerZkLeader = Namer.zkLeader

  val namerd = Namerd.all
  val namerdExamples = Namerd.examples
  val namerdCore = Namerd.core
  val namerdDcosBootstrap = Namerd.dcosBootstrap
  val namerdIfaceControlHttp = Namerd.Iface.controlHttp
  val namerdIfaceInterpreterThriftIdl = Namerd.Iface.interpreterThriftIdl
  val namerdIfaceInterpreterThrift = Namerd.Iface.interpreterThrift
  val namerdStorageEtcd = Namerd.Storage.etcd
  val namerdStorageInMemory = Namerd.Storage.inMemory
  val namerdStorageK8s = Namerd.Storage.k8s
  val namerdStorageZk = Namerd.Storage.zk
  val namerdStorage = Namerd.Storage.all
  val namerdIface = Namerd.Iface.all
  val namerdMain = Namerd.main

  val interpreter = Interpreter.all
  val interpreterNamerd = Interpreter.namerd

  val linkerd = Linkerd.all
  val linkerdBenchmark = Linkerd.Protocol.benchmark
  val linkerdExamples = Linkerd.examples
  val linkerdAdmin = Linkerd.admin
  val linkerdConfig = configCore
  val linkerdCore = Linkerd.core
  val linkerdMain = Linkerd.main
  val linkerdProtocol = Linkerd.Protocol.all
  val linkerdProtocolHttp = Linkerd.Protocol.http
  val linkerdProtocolMux = Linkerd.Protocol.mux
  val linkerdProtocolThrift = Linkerd.Protocol.thrift
  val linkerdTracer = Linkerd.Tracer.all
  val linkerdTracerZipkin = Linkerd.Tracer.zipkin
  val linkerdTls = Linkerd.tls

  // Unified documentation via the sbt-unidoc plugin
  val all = project("all", file("."))
    .settings(unidocSettings)
    .settings(aggregateSettings)
    .aggregate(
      admin,
      configCore,
      consul,
      etcd,
      k8s,
      marathon,
      testUtil,
      Interpreter.all,
      Linkerd.all,
      Namer.all,
      Namerd.all,
      Router.all
    )
}
