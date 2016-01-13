import sbt._
import Keys._
import com.typesafe.sbt.SbtScalariform._
import scalariform.formatter.preferences._

object Base {

  lazy val meta = Seq(
    organization := "io.buoyant",
    version := "0.0.7-SNAPSHOT",
    scalaVersion := "2.11.7",
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
        val contents = (
          "name=%s\nversion=%s\nbuild_revision=%s\nbuild_name=%s"
        ).format(name, ver, buildRev, buildName)
        IO.write(file, contents)
        Seq(file)
      }
  )

  lazy val scalariformPrefs =
    ScalariformKeys.preferences := ScalariformKeys.preferences.value.
      setPreference(DoubleIndentClassDeclaration, false).
      setPreference(PreserveSpaceBeforeArguments, false).
      setPreference(SpacesAroundMultiImports,     false).
      setPreference(SpacesWithinPatternBinders,   false)

  lazy val scalariform =
    (//addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.4.0") ++
      scalariformSettings ++
      Seq(scalariformPrefs))

  //val scoverage =
  //  addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.1.0")

  lazy val BaseSettings =
    meta ++ scalariform

  lazy val EndToEndTest =
    config("e2e") extend Test

  lazy val EndToEndSettings =
    inConfig(EndToEndTest)(Defaults.testSettings)
}
