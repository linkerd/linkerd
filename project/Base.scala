import com.typesafe.sbt.SbtScalariform._
import sbt._
import sbt.Keys._
import scala.language.implicitConversions
import scalariform.formatter.preferences._

/**
 * Base project configuration.
 */
class Base extends Build {

  val scalariformPrefs =
    ScalariformKeys.preferences := ScalariformKeys.preferences.value.
      setPreference(DoubleIndentClassDeclaration, false).
      setPreference(PreserveSpaceBeforeArguments, false).
      setPreference(SpacesAroundMultiImports,     false).
      setPreference(SpacesWithinPatternBinders,   false)

  val EndToEndTest =
    config("e2e") extend Test
  val EndToEndSettings =
    inConfig(EndToEndTest)(Defaults.testSettings)

  // Helper method for constructing projects from directory structure
  def projectDir(dir: String): Project = {
    val id = dir.replaceAll("/", "-")
    Project(id, file(dir))
      .settings(scalariformSettings :+ scalariformPrefs)
      .settings(
        name := id,
        organization := "io.buoyant",
        version := "0.0.8-SNAPSHOT",
        scalaVersion in GlobalScope := "2.11.7",
        scalacOptions ++= Seq("-Xfatal-warnings", "-deprecation"),
        // XXX
        //conflictManager := ConflictManager.strict,
        resolvers ++= Seq(
          "twitter-repo" at "https://maven.twttr.com",
          "local-m2" at ("file:" + Path.userHome.absolutePath + "/.m2/repository"),
          "typesafe" at "https://repo.typesafe.com/typesafe/releases"
        )
      )
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

    def withLib(dep: ModuleID): Project = project
      .settings(libraryDependencies += dep)
    def withLibs(deps: Seq[ModuleID]): Project = project
      .settings(libraryDependencies ++= deps)
    def withLibs(dep: ModuleID, deps: ModuleID*): Project =
      withLibs(dep +: deps)

    /** Enable the test config for a project with basic dependencies */
    def withTests(): Project = project
      .dependsOn(testUtil % "test")

    def withTestLib(dep: ModuleID): Project = withLib(dep % "test")
    def withTestLibs(deps: Seq[ModuleID]): Project = withLibs(deps.map(_ % "test"))
    def withTestLibs(dep: ModuleID, deps: ModuleID*): Project = withTestLibs(dep +: deps)

    /** Enables e2e test config for a project with basic dependencies */
    def withE2e(): Project = project
      .configs(EndToEndTest).settings(EndToEndSettings: _*)
      .dependsOn(testUtil % "e2e")

    def withE2eLib(dep: ModuleID): Project = withLib(dep % "e2e")
    def withE2eLibs(deps: Seq[ModuleID]): Project = withLibs(deps.map(_ % "e2e"))
    def withE2eLibs(dep: ModuleID, deps: ModuleID*): Project = withE2eLibs(dep +: deps)

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
