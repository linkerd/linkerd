import com.typesafe.sbt.SbtScalariform._
import sbt._
import sbt.Keys._
import sbtassembly.AssemblyKeys._
import scala.language.implicitConversions
import scalariform.formatter.preferences._

/**
 * Base project configuration.
 */
class Base extends Build {

  val orgSettings = Seq(
    organization := "io.buoyant",
    version := "0.0.10-SNAPSHOT",
    homepage := Some(url("https://linkerd.io"))
  )

  val scalaSettings = Seq(
    scalaVersion in GlobalScope := "2.11.7",
    scalacOptions ++= Seq("-Xfatal-warnings", "-deprecation")
  )

  val resolverSettings = Seq(
    // XXX
    //conflictManager := ConflictManager.strict,
    resolvers ++= Seq(
      "twitter-repo" at "https://maven.twttr.com",
      "local-m2" at s"file:${Path.userHome.absolutePath}/.m2/repository",
      "typesafe" at "https://repo.typesafe.com/typesafe/releases"
    )
  )

  val scalariformPrefs =
    ScalariformKeys.preferences := ScalariformKeys.preferences.value.
      setPreference(DoubleIndentClassDeclaration, false).
      setPreference(PreserveSpaceBeforeArguments, false).
      setPreference(SpacesAroundMultiImports,     false).
      setPreference(SpacesWithinPatternBinders,   false)

  val EndToEndTest =
    config("e2e") extend Test

  val IntegrationTest =
    config("integration") extend Test

  val configFile = settingKey[File]("path to config file")
  val runtimeConfiguration = settingKey[Configuration]("runtime configuration")

  // Examples are named by a .l5d config file
  def exampleSettings(runtime: Project) = Seq(
    // The example config file should match the example configuration name.
    configFile := file(s"examples/${configuration.value}.l5d"),
    // The runtime configuration may be different from the example configuration.
    runtimeConfiguration := configuration.value,
    run := // call linkerd's run command with a config file
      Def.taskDyn {
        val path = configFile.value.getPath
        (run in runtime in runtimeConfiguration.value).toTask(s" $path")
      }.value
  )

  // Helper method for constructing projects from directory structure
  def projectDir(dir: String): Project = {
    val id = dir.replaceAll("/", "-")
    Project(id, file(dir))
      .settings(name := id)
      .settings(orgSettings)
      .settings(scalaSettings)
      .settings(resolverSettings)
      .settings(scalariformSettings :+ scalariformPrefs)
      .settings(aggregate in assembly := false)
  }

  /**
   * Test utilities (mostly for dealing with async APIs)
   */
  val testUtil = projectDir("test-util")
    .settings(libraryDependencies += Deps.twitterUtil("core"))
    .settings(libraryDependencies += Deps.scalatest)

  /**
   * Extends Project with helpers to reduce boilerplate in project definitions.
   */
  case class ProjectHelpers(project: Project) {

    def configDependsOn(cfg: Configuration)(deps: ProjectReference*): Project =
      project.dependsOn(deps.map(_ % cfg): _*)

    def withLib(dep: ModuleID): Project =
      project.settings(libraryDependencies += dep)

    def withLibs(deps: Seq[ModuleID]): Project =
      project.settings(libraryDependencies ++= deps)

    def withLibs(dep: ModuleID, deps: ModuleID*): Project =
      withLibs(dep +: deps)

    def configWithLibs(cfg: Configuration)(dep: ModuleID, deps: ModuleID*): Project =
      withLibs((dep +: deps).map(_ % cfg))

    /** Enable the test config for a project with basic dependencies */
    def withTests(): Project = project.dependsOn(testUtil % Test)

    /** Enables e2e test config for a project with basic dependencies */
    def withE2e(): Project = project
      .configs(EndToEndTest).settings(inConfig(EndToEndTest)(Defaults.testSettings))
      .dependsOn(testUtil % EndToEndTest)

    def withExamples(runtime: Project, configs: Seq[(Configuration, Configuration)]): Project = {
      val settings = exampleSettings(runtime)
      configs.foldLeft(project) {
        case (project, (egConfig, runConfig)) =>
          project.configs(egConfig)
            .settings(inConfig(egConfig)(settings :+ (runtimeConfiguration := runConfig)))
            .dependsOn(runtime % s"${egConfig}->${runConfig}")
      }
    }

    def withIntegration(): Project = project
      .configs(IntegrationTest).settings(inConfig(IntegrationTest)(Defaults.testSettings))
      .dependsOn(testUtil % IntegrationTest)

    /** Writes build metadata into the projects resources */
    def withBuildProperties(): Project = project
      .settings((resourceGenerators in Compile) <+=
        (resourceManaged in Compile, name, version).map { (dir, name, ver) =>
          val rev = Process("git" :: "rev-parse" :: "HEAD" :: Nil).!!.trim
          val build = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date)
          val contents = s"name=$name\nversion=$ver\nbuild_revision=$rev\nbuild_name=$build"
          val file = dir / "io" / "buoyant" / name / "build.properties"
          IO.write(file, contents)
          Seq(file)
        })
  }

  implicit def pimpMyProject(p: Project): ProjectHelpers =  ProjectHelpers(p)
}
