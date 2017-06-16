import sbt._

object Deps {

  val curatorFramework = "org.apache.curator" % "curator-framework" % "2.9.1"
  val curatorClient = "org.apache.curator" % "curator-client" % "2.9.1"
  val curatorDiscovery = "org.apache.curator" % "curator-x-discovery" % "2.9.1"

  // process lifecycle
  val twitterServer =
    ("com.twitter" %% "twitter-server" % "1.29.0")
      .exclude("com.twitter", "finagle-zipkin_2.12")

  def twitterUtil(mod: String) =
    "com.twitter" %% s"util-$mod" % "6.43.0"

  // networking
  def finagle(mod: String) =
    "com.twitter" %% s"finagle-$mod" % "6.44.0"

  def netty4(mod: String) =
    "io.netty" % s"netty-$mod" % "4.1.9.Final"

  val boringssl = "io.netty" % "netty-tcnative-boringssl-static" % "2.0.0.Final"

  def zkCandidate = "com.twitter.common.zookeeper" % "candidate" % "0.0.76"

  // Jackson (parsing)
  val jacksonVersion = "2.8.4"
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

  // parses a variety of timestamp formats (like RFC3339)
  val jodaTime = Seq(
    "joda-time" % "joda-time"    % "2.7",
    "org.joda"  % "joda-convert" % "1.7"
  )

  // testing. duh.
  val scalatest = "org.scalatest" %% "scalatest" % "3.0.1"

  // guava
  val guava = "com.google.guava" % "guava" % "19.0"

  // jwt for Marathon API
  val jwt = "com.pauldijou" %% "jwt-core" % "0.12.1"

  val protobuf = "com.google.protobuf" % "protobuf-java" % "3.3.1"

  // statsd client
  val statsd = "com.datadoghq" % "java-dogstatsd-client" % "2.3"
}
