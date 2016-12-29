
/** Finagle protocol extensions. */
object Finagle extends Base{

  val h2 = projectDir("finagle/h2")
    .withTwitterLibs(Deps.finagle("netty4"))
    .withLibs(Deps.netty4("codec-http2"), Deps.netty4("handler"))
    .withTests()
    .withE2e()

  val all = aggregateDir("finagle", h2)
}
