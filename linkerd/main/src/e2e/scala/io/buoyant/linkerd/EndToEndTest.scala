package io.buoyant.linkerd

import com.twitter.finagle.Http
import com.twitter.finagle.http.{Request, Status}
import io.buoyant.linkerd.Linker.Initializers
import io.buoyant.linkerd.protocol.HttpInitializer
import io.buoyant.namerd.iface.NamerdInterpreterInitializer
import io.buoyant.test.FunSuite
import io.buoyant.transformer.perHost.{LocalhostTransformerInitializer, PortTransformerInitializer}
import java.net.ServerSocket

class EndToEndTest extends FunSuite {

  test("interpreter state") {

    // A somewhat racey and unsafe way of getting an ephemeral port.
    val ss = new ServerSocket(0)
    val adminPort = ss.getLocalPort
    ss.close()

    val config =
      s"""namers: []
        |admin:
        |  port: $adminPort
        |routers:
        |- protocol: http
        |  label: out
        |  interpreter:
        |    kind: io.l5d.namerd
        |    namespace: dtab-out
        |    dst: /$$/inet/localhost/4100
        |    transformers:
        |    - kind: io.l5d.port
        |      port: 4141
        |  servers:
        |  - port: 4140
        |
        |- protocol: http
        |  label: in
        |  interpreter:
        |    kind: io.l5d.namerd
        |    namespace: dtab-in
        |    dst: /$$/inet/localhost/4100
        |    transformers:
        |    - kind: io.l5d.localhost
        |  servers:
        |  - port: 4141
    """.stripMargin

    val linkerConfig = Linker.parse(config, Initializers(
      protocol = Seq(HttpInitializer),
      interpreter = Seq(NamerdInterpreterInitializer),
      transformer = Seq(new PortTransformerInitializer, new LocalhostTransformerInitializer)
    ))
    val linker = linkerConfig.mk()

    val closables = Main.initAdmin(linkerConfig, linker)

    try {
      val client = Http.newService(s"/$$/inet/localhost/$adminPort")
      assert(
        await(client(Request("/out/interpreter_state/io.l5d.namerd/dtab-out.json"))).status ==
          Status.Ok
      )
      assert(
        await(client(Request("/in/interpreter_state/io.l5d.namerd/dtab-in.json"))).status ==
          Status.Ok
      )
    } finally {
      for(c <- closables) c.close()
    }
  }
}
