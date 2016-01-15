import sbt._
import Keys._
import com.typesafe.sbt.SbtScalariform._
import sbtunidoc.Plugin._
import scalariform.formatter.preferences._

object LinkerdBuild extends Build {

  val meta = Seq(
    organization := "io.buoyant",
    version := "0.0.8-SNAPSHOT",
    scalaVersion in GlobalScope := "2.11.7",
    scalacOptions ++= Seq(
      "-Xfatal-warnings",
      "-deprecation"
    ),
    // XXX
    //conflictManager := ConflictManager.strict,
    resolvers ++= Seq(
      "twitter-repo" at "https://maven.twttr.com",
      "local-m2" at ("file:" + Path.userHome.absolutePath + "/.m2/repository"),
      "typesafe" at "https://repo.typesafe.com/typesafe/releases"
    ),
    resourceGenerators in Compile <+=
      (resourceManaged in Compile, name, version) map { (dir, name, ver) =>
        val file = dir / "io" / "buoyant" / name / "build.properties"
        val buildRev = Process("git" :: "rev-parse" :: "HEAD" :: Nil).!!.trim
        val buildName = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date)
        val contents =
          "name=%s\nversion=%s\nbuild_revision=%s\nbuild_name=%s"
          .format(name, ver, buildRev, buildName)
        IO.write(file, contents)
        Seq(file)
      }
  )

  val scalariformPrefs =
    ScalariformKeys.preferences := ScalariformKeys.preferences.value.
      setPreference(DoubleIndentClassDeclaration, false).
      setPreference(PreserveSpaceBeforeArguments, false).
      setPreference(SpacesAroundMultiImports,     false).
      setPreference(SpacesWithinPatternBinders,   false)

  val scalariform =
    scalariformSettings ++ Seq(scalariformPrefs)

  val BaseSettings =
    meta ++ scalariform

  val EndToEndTest =
    config("e2e") extend Test

  val EndToEndSettings =
    inConfig(EndToEndTest)(Defaults.testSettings)

  // Helper method for constructing projects from directory structure
  def standardProject(dir: String): Project = {
    val id = dir.replaceAll("/", "-")
    Project(
      id = id,
      base = file(dir),
      settings = BaseSettings :+ (name := id)
    ).configs(EndToEndTest).settings(EndToEndSettings: _*)
  }

  // Some utility code for writing tests around Finagle's async apis.
  val testUtil = standardProject("test-util").settings(
    libraryDependencies ++=
      Deps.twitterUtil("core") ::
      Deps.scalatest ::
      Nil
  )

  // Finagle Kubernetes API and namer
  val k8s = standardProject("k8s").settings(
    libraryDependencies ++=
      Deps.finagle("http") ::
      Deps.scalatest % "test" ::
      Deps.jackson
  ).dependsOn(testUtil % "test")

  // Finagle-based Router
  object Router {
    val core = standardProject("router/core").settings(
      libraryDependencies ++=
        Deps.finagle("core") ::
        Deps.finagle("stats") % "e2e" ::
        Deps.scalatest % "e2e,test" ::
        Nil
    ).dependsOn(testUtil % "e2e,test")

    val http = standardProject("router/http").settings(
      libraryDependencies ++=
        Deps.finagle("http") ::
        Deps.finagle("stats") % "e2e" ::
        Deps.scalatest % "test" ::
        Nil
    ).dependsOn(core, testUtil % "e2e,test")

    val mux = standardProject("router/mux").settings(
      libraryDependencies ++=
        Deps.finagle("mux") ::
        Deps.finagle("stats") % "e2e" ::
        Deps.scalatest % "test" ::
        Nil
    ).dependsOn(core, testUtil % "e2e,test")

    val thrift = standardProject("router/thrift").settings(
      libraryDependencies ++=
        Deps.finagle("thrift") ::
        Deps.finagle("stats") % "e2e" ::
        Deps.scalatest % "test" ::
        Nil
    ).dependsOn(core, testUtil % "e2e,test")

    val all = standardProject("router").aggregate(core, http, mux, thrift)
  }

  // Linkerd: Router configuration & runtime
  object Linkerd {
    // A library that supports configuration of router-core types using
    // jackson-core's JsonParser API.
    val core = standardProject("linkerd/core").settings(
      libraryDependencies ++=
        Deps.finagle("core") ::
        Deps.twitterUtil("core") ::
        Deps.jacksonCore ::
        // the library doesn't require data-binding, but it's useful for tests
        Deps.scalatest % "test" ::
        Deps.jacksonDatabind % "test" ::
        Deps.jacksonYaml % "test" ::
        Nil
    ).dependsOn(Router.core)

    // Administrative web UI for introspecting Linkerd state.
    val admin = standardProject("linkerd/admin").settings(
      libraryDependencies ++=
        Deps.twitterServer ::
        Deps.scalatest % "test" ::
        Nil
    ).dependsOn(core, core % "test->test", testUtil % "test")

    // A runtime that uses linkerd-core to process configuration.
    val main = standardProject("linkerd/main").settings(
      libraryDependencies ++=
        Deps.jacksonCore ::
        Deps.jacksonDatabind ::
        Deps.jacksonYaml ::
        Deps.twitterServer ::
        Nil
    ).dependsOn(admin, core)

    object Namer {
      // A namer initializer for the fs namer.
      val fs = standardProject("linkerd/namer/fs").settings(
        libraryDependencies += Deps.finagle("core")
      ).dependsOn(core)

      val k8s = standardProject("linkerd/namer/k8s").settings(
        libraryDependencies ++=
          Deps.finagle("core") ::
          Deps.scalatest % "test" ::
          Nil
      ).dependsOn(LinkerdBuild.k8s, core)

      val all = standardProject("linkerd/namer").aggregate(fs, k8s)
    }

    object Protocol {
      val http = standardProject("linkerd/protocol/http").settings(
        libraryDependencies ++=
          Deps.finagle("http") ::
          Deps.scalatest % "test" ::
          Nil
      ).dependsOn(core, Router.http)

      val mux = standardProject("linkerd/protocol/mux").settings(
        libraryDependencies ++=
          Deps.finagle("mux") ::
          Deps.scalatest % "test" ::
          Nil
      ).dependsOn(core, Router.mux)

      val thrift = standardProject("linkerd/protocol/thrift").settings(
        libraryDependencies ++=
          Deps.finagle("thrift") ::
          Deps.scalatest % "test" ::
          Nil
      ).dependsOn(core, Router.thrift)

      val all = standardProject("linkerd/protocol").aggregate(http, mux, thrift)
    }

    val all = standardProject("linkerd").aggregate(
      admin,
      core,
      main,
      Namer.all,
      Protocol.all
    )
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

  val all = Project(
    id = "all",
    base = file("."),
    // Unified documentation via the sbt-unidoc plugin
    settings = unidocSettings
  ).aggregate(k8s, linkerd, router, testUtil)

}
