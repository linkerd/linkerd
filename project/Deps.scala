import sbt._

object Deps {

  // process lifecycle
  val twitterServer =
    ("com.twitter" %% "twitter-server" % "1.16.0").
      exclude("com.twitter", "finagle-zipkin_2.11")

  def twitterUtil(mod: String) =
    "com.twitter" %% s"util-$mod" % "6.30.0"

  // networking
  def finagle(mod: String) =
    "com.twitter" %% s"finagle-$mod" % "6.31.0"

  // Jackson (parsing)
  val jacksonVersion = "2.4.4"
  val jacksonCore =
    "com.fasterxml.jackson.core" % "jackson-core" % jacksonVersion
  val jacksonAnnotations =
    "com.fasterxml.jackson.core" % "jackson-annotations" % jacksonVersion
  val jacksonDatabind =
    "com.fasterxml.jackson.core" % "jackson-databind" % jacksonVersion
  val jacksonScala =
    "com.fasterxml.jackson.module" %% "jackson-module-scala" % jacksonVersion 
  val jackson =
    jacksonCore :: jacksonAnnotations :: jacksonDatabind :: jacksonScala :: Nil

  val jacksonYaml =
    "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml" % jacksonVersion

  // cats provides a variety of functional programming abstractions. We use it for validation.
  val cats =
    "org.typelevel" %% "cats" % "0.4.0"

  // testing. duh.
  val scalatest = "org.scalatest" %% "scalatest" % "2.2.4"
}
