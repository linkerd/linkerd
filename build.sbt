import Base._

lazy val root =
  project.in(file(".")).
    settings(unidocSettings: _*).
    aggregate(
      k8s,
      linkerd,
      router,
      `test-util`
    )
//
// Utility code
//
// ----
// Some utility code for writing tests around finagle's async apis.
lazy val `test-util` =
  project.in(file("test-util")).
    settings(BaseSettings:_*).settings(
      name := "test-util",
      libraryDependencies ++=
        Deps.twitterUtil("core") ::
        Deps.scalatest  ::
        Nil
    )
//
// Clients for other services, especially coordination backends
//
// ----
// finagle kubernetes api and namer
lazy val k8s =
  project.in(file("k8s")).
    settings(BaseSettings:_*).settings(
      name := "k8s",
      libraryDependencies ++=
        Deps.finagle("http") ::
        Deps.scalatest % "test" ::
        Deps.jackson
    ).dependsOn(
      `test-util` % "test"
    )
//
// Finagle-based Router
//
lazy val router =
  project.in(file("router")).aggregate(
    `router-core`,
    `router-http`,
    `router-mux`,
    `router-thrift`
  )
lazy val `router-core` =
  project.in(file("router/core")).
    configs(EndToEndTest).settings(EndToEndSettings: _*).
    settings(BaseSettings:_*).settings(
      name := "router-core",
      libraryDependencies ++=
        Deps.finagle("core") ::
        Deps.finagle("stats") % "e2e" ::
        Deps.scalatest % "e2e,test" ::
        Nil
    ).dependsOn(
      `test-util` % "e2e,test"
    )
lazy val `router-http` =
  project.in(file("router/http")).
    configs(EndToEndTest).settings(EndToEndSettings: _*).
    settings(BaseSettings:_*).settings(
      name := "router-http",
      libraryDependencies ++=
        Deps.finagle("http") ::
        Deps.finagle("stats") % "e2e" ::
        Deps.scalatest % "test" ::
        Nil
    ).dependsOn(
      `router-core`,
      `test-util` % "e2e,test"
    )
lazy val `router-mux` =
  project.in(file("router/mux")).
    configs(EndToEndTest).settings(EndToEndSettings: _*).
    settings(BaseSettings:_*).settings(
      name := "router-mux",
      libraryDependencies ++=
        Deps.finagle("mux") ::
        Deps.finagle("stats") % "e2e" ::
        Deps.scalatest % "test" ::
        Nil
    ).dependsOn(
      `router-core`,
      `test-util` % "e2e,test"
    )
lazy val `router-thrift` =
  project.in(file("router/thrift")).
    configs(EndToEndTest).settings(EndToEndSettings: _*).
    settings(BaseSettings:_*).settings(
      name := "router-thift",
      libraryDependencies ++=
        Deps.finagle("thrift") ::
        Deps.finagle("stats") % "e2e" ::
        Deps.scalatest % "test" ::
        Nil
    ).dependsOn(
      `router-core`,
      `test-util` % "e2e,test"
    )
//
// Linkerd: Router configuration & runtime
//
lazy val linkerd =
  project.in(file("linkerd")).aggregate(
    `linkerd-admin`,
    `linkerd-core`,
    `linkerd-main`,
    `linkerd-namer-fs`,
    `linkerd-namer-k8s`,
    `linkerd-protocol-http`,
    `linkerd-protocol-mux`,
    `linkerd-protocol-thrift`
  )
// A library that supports configuration of router-core types using
// jackson-core's JsonParser API.
lazy val `linkerd-core` =
  project.in(file("linkerd/core")).
    settings(BaseSettings:_*).settings(
      name := "linkerd-core",
      libraryDependencies ++=
        Deps.finagle("core") ::
        Deps.twitterUtil("core") ::
        Deps.jacksonCore ::
        // the library code doesn't require data-binding, but it's
        // useful for tests:
        Deps.scalatest % "test" ::
        Deps.jacksonDatabind % "test" ::
        Deps.jacksonYaml % "test" ::
        Nil
    ).dependsOn(
      `router-core`
    )
// ----
// Administrative web UI for introspecting Linkerd state.
lazy val `linkerd-admin` =
  project.in(file("linkerd/admin")).
    settings(BaseSettings:_*).settings(
      name := "linkerd-admin",
      libraryDependencies ++=
        Deps.twitterServer ::
        Deps.scalatest % "test" ::
        Nil
    ).dependsOn(
      `linkerd-core`,
      `linkerd-core` % "test->test",
      `test-util` % "test"
    )
// ----
// A runtime that uses linkerd-core to process configuration.
lazy val `linkerd-main` =
  project.in(file("linkerd/main")).
    settings(BaseSettings:_*).settings(
      name := "linkerd-main",
      libraryDependencies ++=
        Deps.jacksonCore ::
        Deps.jacksonDatabind ::
        Deps.jacksonYaml ::
        Deps.twitterServer ::
        Nil
    ).dependsOn(
      `linkerd-admin`,
      `linkerd-core`
    )
// ----
// A namer initializer for the fs namer.
lazy val `linkerd-namer-fs` =
  project.in(file("linkerd/namer/fs")).
    settings(BaseSettings:_*).settings(
      name := "linkerd-namer-fs",
      libraryDependencies += Deps.finagle("core")
    ).dependsOn(
      `linkerd-core`
    )
//
// Namer extensions
//
lazy val `linkerd-namer-k8s` =
  project.in(file("linkerd/namer/k8s")).
    settings(BaseSettings:_*).settings(
      name := "linkerd-namer-k8s",
      libraryDependencies += Deps.finagle("core")
    ).dependsOn(
      `k8s`,
      `linkerd-core`
    )
//
// Protocol-specific extensions
//
lazy val `linkerd-protocol-http` =
  project.in(file("linkerd/protocol/http")).
    settings(BaseSettings:_*).settings(
      name := "linkerd-protocol-http",
      libraryDependencies ++=
        Deps.finagle("http") ::
        Deps.scalatest % "test" ::
        Nil
    ).dependsOn(
      `linkerd-core`,
      `router-http`
    )
lazy val `linkerd-protocol-mux` =
  project.in(file("linkerd/protocol/mux")).
    settings(BaseSettings:_*).settings(
      name := "linkerd-protocol-mux",
      libraryDependencies ++=
        Deps.finagle("mux") ::
        Deps.scalatest % "test" ::
        Nil
    ).dependsOn(
      `linkerd-core`,
      `router-mux`
    )
lazy val `linkerd-protocol-thrift` =
  project.in(file("linkerd/protocol/thrift")).
    settings(BaseSettings:_*).settings(
      name := "linkerd-protocol-thrift",
      libraryDependencies ++=
        Deps.finagle("thrift") ::
        Deps.scalatest % "test" ::
        Nil
    ).dependsOn(
      `linkerd-core`,
      `router-thrift`
    )
