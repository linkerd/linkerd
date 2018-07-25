package io.buoyant.linkerd.protocol

import com.twitter.finagle.http.{Request, Response}
import io.buoyant.linkerd.Linker
import io.buoyant.linkerd.tls.TlsUtils.withCerts
import io.buoyant.router.http.ForwardClientCertFilter
import io.buoyant.test.FunSuite
import java.io.FileInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import javax.xml.bind.DatatypeConverter.printHexBinary

class ForwardClientCertTest extends FunSuite {

  test("forward client certificate") {
    withCerts("upstream", "linkerd") { certs =>
      var downstreamRequest: Request = null
      val dog = Downstream.mk("dogs") { req =>
        downstreamRequest = req
        val rsp = Response()
        rsp.contentString = "woof"
        rsp
      }

      val linkerConfig =
        s"""
           |routers:
           |- protocol: http
           |  dtab: |
           |    /p/dog => /$$/inet/127.1/${dog.port} ;
           |    /svc/clifford => /p/dog ;
           |  servers:
           |  - port: 0
           |    tls:
           |      certPath: ${certs.serviceCerts("linkerd").cert.getPath}
           |      keyPath: ${certs.serviceCerts("linkerd").key.getPath}
           |      caCertPath: ${certs.caCert.getPath}
           |      requireClientAuth: true
           |  client:
           |    kind: io.l5d.global
           |    forwardClientCert: true
           |""".stripMargin
      val linker = Linker.load(linkerConfig)
      val router = linker.routers.head.initialize()
      val server = router.servers.head.serve()

      val upstreamServiceCert = certs.serviceCerts("upstream")
      val client = Upstream.mkTls(server, "linkerd", certs.caCert, Some(upstreamServiceCert))

      try {
        val rsp = {
          val req = Request()
          req.host = "clifford"
          await(client(req))
        }

        assert(rsp.contentString == "woof")
        assert(downstreamRequest.headerMap(ForwardClientCertFilter.Header) == {
          val cf = CertificateFactory.getInstance("X.509")
          val cert = cf.generateCertificate(new FileInputStream(upstreamServiceCert.cert))
          val digest = MessageDigest.getInstance("SHA-256")
          s"""Hash=${printHexBinary(digest.digest(cert.getEncoded))};SAN=https://buoyant.io;DNS=upstream;DNS=linkerd;Subject="C=US,CN=upstream""""
        })
        ()
      } finally {
        await(client.close())
        await(server.close())
        await(router.close())
        await(dog.server.close())
      }
    }
  }

  test("clears incoming client certificate") {
    var downstreamRequest: Request = null
    val dog = Downstream.mk("dogs") { req =>
      downstreamRequest = req
      val rsp = Response()
      rsp.contentString = "woof"
      rsp
    }

    val linkerConfig =
      s"""
         |routers:
         |- protocol: http
         |  dtab: |
         |    /p/dog => /$$/inet/127.1/${dog.port} ;
         |    /svc/clifford => /p/dog ;
         |  servers:
         |  - port: 0
         |  client:
         |    kind: io.l5d.global
         |""".stripMargin
    val linker = Linker.load(linkerConfig)
    val router = linker.routers.head.initialize()
    val server = router.servers.head.serve()

    val client = Upstream.mk(server)

    try {
      val rsp = {
        val req = Request()
        req.host = "clifford"
        req.headerMap(ForwardClientCertFilter.Header) = "totally_bogus"
        await(client(req))
      }

      assert(rsp.contentString == "woof")
      assert(!downstreamRequest.headerMap.contains(ForwardClientCertFilter.Header))
    } finally {
      await(client.close())
      await(server.close())
      await(router.close())
      await(dog.server.close())
    }
  }
}
