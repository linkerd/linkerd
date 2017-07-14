package io.buoyant.linkerd.protocol

import com.twitter.finagle.http.{Request, Status}
import io.buoyant.linkerd.Linker
import io.buoyant.linkerd.protocol.TlsUtils.Downstream
import io.buoyant.test.FunSuite
import java.net.InetSocketAddress

class ClientAuthTest extends FunSuite {

  test("client auth") {
    TlsUtils.withCerts("client", "server") { certs =>
      val downstream = Downstream.const("foo", "foo")

      val serverCerts = certs.serviceCerts("server")
      val serverConfig = s"""
        |namers: []
        |
        |routers:
        |- protocol: http
        |  dtab: /svc/* => /$$/inet/127.1/${downstream.port} ;
        |  servers:
        |  - port: 0
        |    tls:
        |      requireClientAuth: true
        |      certPath: ${serverCerts.cert.getPath}
        |      keyPath: ${serverCerts.key.getPath}
        |      caCertPath: ${certs.caCert.getPath}
      """.stripMargin
      val serverLinker = Linker.load(serverConfig)
      val serverRouter = serverLinker.routers.head.initialize()
      val serverServer = serverRouter.servers.head.serve()
      val serverPort = serverServer.boundAddress.asInstanceOf[InetSocketAddress].getPort

      val clientCerts = certs.serviceCerts("client")
      val clientConfig = s"""
         |namers: []
         |
         |routers:
         |- protocol: http
         |  dtab: /svc/* => /$$/inet/127.1/$serverPort ;
         |  servers:
         |  - port: 0
         |  client:
         |   tls:
         |     commonName: server
         |     trustCerts:
         |     - ${certs.caCert.getPath}
         |     clientAuth:
         |       certPath: ${clientCerts.cert.getPath}
         |       keyPath: ${clientCerts.key.getPath}
       """.stripMargin
      val clientLinker = Linker.load(clientConfig)
      val clientRouter = clientLinker.routers.head.initialize()
      val clientServer = clientRouter.servers.head.serve()

      val upstream = TlsUtils.upstream(clientServer)

      try {
        val req = Request()
        req.host = "foo"
        val rsp = await(upstream(req))
        assert(rsp.status == Status.Ok)
        assert(rsp.contentString == "foo")
        ()
      } finally {
        await(upstream.close())
        await(clientServer.close())
        await(clientRouter.close())
        await(serverServer.close())
        await(serverRouter.close())
        await(downstream.server.close())
      }
    }
  }

  test("require client auth") {
    TlsUtils.withCerts("server") { certs =>
      val downstream = Downstream.const("foo", "foo")

      val serverCerts = certs.serviceCerts("server")
      val serverConfig = s"""
                            |namers: []
                            |
                            |routers:
                            |- protocol: http
                            |  dtab: /svc/* => /$$/inet/127.1/${downstream.port} ;
                            |  servers:
                            |  - port: 0
                            |    tls:
                            |      requireClientAuth: true
                            |      certPath: ${serverCerts.cert.getPath}
                            |      keyPath: ${serverCerts.key.getPath}
                            |      caCertPath: ${certs.caCert.getPath}
      """.stripMargin
      val serverLinker = Linker.load(serverConfig)
      val serverRouter = serverLinker.routers.head.initialize()
      val serverServer = serverRouter.servers.head.serve()
      val serverPort = serverServer.boundAddress.asInstanceOf[InetSocketAddress].getPort

      val clientConfig = s"""
                            |namers: []
                            |
                            |routers:
                            |- protocol: http
                            |  dtab: /svc/* => /$$/inet/127.1/$serverPort ;
                            |  servers:
                            |  - port: 0
                            |  client:
                            |   tls:
                            |     commonName: server
                            |     trustCerts:
                            |     - ${certs.caCert.getPath}
       """.stripMargin
      val clientLinker = Linker.load(clientConfig)
      val clientRouter = clientLinker.routers.head.initialize()
      val clientServer = clientRouter.servers.head.serve()

      val upstream = TlsUtils.upstream(clientServer)

      try {
        val req = Request()
        req.host = "foo"
        val rsp = await(upstream(req))
        assert(rsp.status == Status.BadGateway)
        ()
      } finally {
        await(upstream.close())
        await(clientServer.close())
        await(clientRouter.close())
        await(serverServer.close())
        await(serverRouter.close())
        await(downstream.server.close())
      }
    }
  }
}
