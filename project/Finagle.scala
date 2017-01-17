import sbt._

/** Finagle protocol extensions. */
object Finagle extends Base {

  val buoyantCore = projectDir("finagle/buoyant")
    .withTwitterLibs(Deps.finagle("netty4"))
    .withTests()

  val h2 = projectDir("finagle/h2")
    .dependsOn(buoyantCore)
    .withLibs(
      Deps.netty4("codec-http2"), Deps.netty4("handler"),
      "io.netty" % "netty-tcnative-boringssl-static" % "1.1.33.Fork23")
    .withTests()
    .withE2e()

  val all = aggregateDir("finagle", buoyantCore, h2)
}
