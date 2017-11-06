package io.buoyant.linkerd.protocol

import java.net.InetSocketAddress

import com.twitter.finagle.http.{Request, Status}
import io.buoyant.linkerd.Linker
import io.buoyant.linkerd.protocol.TlsUtils.{Certs, Downstream}
import io.buoyant.test.{Awaits, FunSuite}

class TlsCertReloadingTest extends FunSuite with Awaits {

  test("cert reloading") {
    TlsUtils.withCerts("foo", "bar") { certs =>
      val downstream = Downstream.const("foo", "foo")

      val fooCerts = certs.serviceCerts("foo")
      val barCerts = certs.serviceCerts("bar")

      val incomingConfig =
        s"""
        |namers: []
        |
        |routers:
        |- protocol: http
        |  dtab: /svc/* => /$$/inet/127.1/${downstream.port} ;
        |  servers:
        |  - port: 0
        |    tls:
        |      certPath: ${fooCerts.cert.getPath}
        |      keyPath: ${fooCerts.key.getPath}
        |      caCertPath: ${certs.caCert.getPath}
      """.stripMargin
      val incomingLinker = Linker.load(incomingConfig)
      val incomingRouter = incomingLinker.routers.head.initialize()
      val incomingServer = incomingRouter.servers.head.serve()
      val serverPort = incomingServer.boundAddress.asInstanceOf[InetSocketAddress].getPort


      val outgoingConfig = s"""
        |namers: []
        |
        |routers:
        |- protocol: http
        |  dtab: /svc/* => /$$/inet/127.1/$serverPort ;
        |  servers:
        |  - port: 0
        |  client:
        |   tls:
        |     commonName: bar
        |     trustCerts:
        |     - ${certs.caCert.getPath}
       """.stripMargin
      val outgoingLinker = Linker.load(outgoingConfig)
      val outgoingRouter = outgoingLinker.routers.head.initialize()
      val outgoingServer = outgoingRouter.servers.head.serve()

      val upstream = TlsUtils.upstream(outgoingServer)

      try {
        // build request
        val req = Request()
        req.host = "foo"

        // request before the certs are renamed should fail
        withClue("before renaming certs") {
          val rsp = await(upstream(req))
          assert(rsp.status == Status.BadGateway)
        }

        // move the "bar" certs to the "foo" certs' path
        withClue("renaming certs") {
          assert(fooCerts.delete().isReturn)
          assert(barCerts.renameTo("foo").isReturn)
        }

        // now, the request should succeed
        withClue("after renaming certs") {
          val rsp = await(upstream(req))
          assert(rsp.status == Status.Ok)
          assert(rsp.contentString == "foo")
        }
        ()
      } finally {
        await(upstream.close())
        await(outgoingServer.close())
        await(outgoingRouter.close())
        await(incomingServer.close())
        await(incomingRouter.close())
        await(downstream.server.close())
      }
    }
  }

}
