import sbt._

object Deps {

  // process lifecycle
  val twitterServer =
    ("com.twitter" %% "twitter-server" % "1.30.0")
      .exclude("com.twitter", "finagle-zipkin_2.12")

  def twitterUtil(mod: String) =
    "com.twitter" %% s"util-$mod" % "6.45.0"

  // networking
  def finagle(mod: String) =
    "com.twitter" %% s"finagle-$mod" % "6.45.0"

  def netty4(mod: String) =
    "io.netty" % s"netty-$mod" % "4.1.10.Final"

  // Original tcnative version: "io.netty" % "netty-tcnative-boringssl-static" % "2.0.1.Final"
  // Since we need FIPS compliance, we're using the dynamic binding version (for centos)
  val boringssl = "io.netty" % "netty-tcnative" % "2.0.1.Final" classifier "linux-x86_64-fedora" //classifier "osx-x86_64"

  def zkCandidate =
    ("com.twitter.common.zookeeper" % "candidate" % "0.0.84")
      .exclude("com.twitter.common", "util")

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

  // scalacheck for Property-based testing
  val scalacheck = "org.scalacheck" %% "scalacheck" % "1.13.4"

  // junit
  val junit = "junit" % "junit" % "4.10"

  // guava
  val guava = "com.google.guava" % "guava" % "21.0"

  // jwt for Marathon API
  val jwt = "com.pauldijou" %% "jwt-core" % "0.12.1"

  val protobuf = "com.google.protobuf" % "protobuf-java" % "3.3.1"

  // statsd client
  val statsd = "com.datadoghq" % "java-dogstatsd-client" % "2.3"

  // curator service discovery
  val curatorSD = "org.apache.curator" % "curator-x-discovery" % "4.0.0" exclude("org.apache.zookeeper", "zookeeper")

  val zookeeper = "org.apache.zookeeper" % "zookeeper" % "3.4.10"

  // Medallia service discovery, not transitive for now because it depends on kafka which has an incompatible scala version
  // Dependencies are obtained from curatorSD at the moment
  // We should decouple service discovery in rpc-library from everything else
  val rpcLibrary = "com.medallia" % "rpc-library" % "2.1.1" intransitive()

  // kafka
  val kafka = "org.apache.kafka" % "kafka_2.12" % "0.10.1.1"
}
