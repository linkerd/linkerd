import pl.project13.scala.sbt.JmhPlugin
import sbt.Keys.publishArtifact
import sbt._

/** Finagle protocol extensions. */
object Finagle extends Base {

  val buoyantCore = projectDir("finagle/buoyant")
    .withTwitterLibs(Deps.finagle("netty4"))
    .withTests()

  val h2 = projectDir("finagle/h2")
    .dependsOn(buoyantCore)
    .withLibs(
      Deps.netty4("codec-http2"), Deps.netty4("handler"), Deps.boringssl
    )
    .withTests()
    .withE2e()

  val benchmark = projectDir("finagle/benchmark")
    .dependsOn(h2, buoyantCore, testUtil)
    .enablePlugins(JmhPlugin)
    .settings(publishArtifact := false)
    .withTwitterLib(Deps.twitterUtil("benchmark"))

  val all = aggregateDir("finagle", buoyantCore, h2, benchmark)
}
