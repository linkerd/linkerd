import sbt._

object Deps {

  val curatorFramework = "org.apache.curator" % "curator-framework" % "4.1.0"
  val curatorClient = "org.apache.curator" % "curator-client" % "4.1.0"
  val curatorDiscovery = "org.apache.curator" % "curator-x-discovery" % "4.1.0"

  // process lifecycle
  val twitterServer =
    ("com.twitter" %% "twitter-server" % "19.5.1")
      .exclude("com.twitter", "finagle-zipkin_2.12")

  def twitterUtil(mod: String) =
    "com.twitter" %% s"util-$mod" % "19.5.1"

  def twitterCommonUtil = "com.twitter.common" % "util" % "0.0.121"

  def twitteCommonBase = "com.twitter.common" % "base" % "0.0.116"

  // networking
  def finagle(mod: String) =
    "com.twitter" %% s"finagle-$mod" % "19.5.1"

  def netty4(mod: String) =
    "io.netty" % s"netty-$mod" % "4.1.31.Final"

  val boringssl = "io.netty" % "netty-tcnative-boringssl-static" % "2.0.19.Final"

  def apacheZookeeper = ("org.apache.zookeeper" % "zookeeper" % "3.5.0-alpha")
    .exclude("com.sun.jdmk", "jmxtools")
    .exclude("com.sun.jmx", "jmxri")
    .exclude("javax.jms", "jms")

  def apacheCommonsLang = "commons-lang" % "commons-lang" % "2.6"

  // Jackson (parsing)
  val jacksonVersion = "2.9.6"
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

  // scalacheck for Property-based testing
  val scalacheck = "org.scalacheck" %% "scalacheck" % "1.13.4"

  // junit
  val junit = "junit" % "junit" % "4.10"

  // guava
  val guava = "com.google.guava" % "guava" % "23.0"

  // jwt for Marathon API
  val jwt = "com.pauldijou" %% "jwt-core" % "0.12.1"

  val protobuf = "com.google.protobuf" % "protobuf-java" % "3.3.1"

  // statsd client
  val statsd = "com.datadoghq" % "java-dogstatsd-client" % "2.3"

  // dnsjava
  val dnsJava = "dnsjava" % "dnsjava" % "2.1.8"
}
