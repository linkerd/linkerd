package io.buoyant.linkerd.protocol.h2

import com.twitter.finagle.buoyant.h2.{Method, Request, Response, Status, Stream}
import com.twitter.util.Future
import io.buoyant.linkerd.Linker
import io.buoyant.linkerd.tls.TlsUtils.withCerts
import io.buoyant.test.FunSuite
import io.buoyant.test.h2.StreamTestUtils._
import java.io.FileInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import javax.xml.bind.DatatypeConverter.printHexBinary

class ForwardClientCertTest extends FunSuite {

  test("forward client certificate") {
    testForwardedClient()
  }

  test("forward client certificate with existing x-forwarded-client-cert header") {
    testForwardedClient(Some("""Hash=ABC;SAN=https://spoof.io;Subject="C=US,CN=root"""))
  }

  private def testForwardedClient(xForwardedClientCert: Option[String] = None) = {
    withCerts("upstream", "linkerd") { certs =>
      var downstreamRequest: Request = null
      val dog = Downstream.mk("dogs") { req =>
        downstreamRequest = req
        Future.value(Response(Status.Ok, Stream.const("woof")))
      }

      val linkerConfig =
        s"""
           |routers:
           |- protocol: h2
           |  experimental: true
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
        val request = Request("http", Method.Get, "clifford", "/", Stream.empty())
        xForwardedClientCert.foreach(h => request.headers.add("x-forwarded-client-cert", h))
        val rsp = await(client(request))

        assert(await(rsp.stream.readDataString) == "woof")
        assert(downstreamRequest.headers.get("x-forwarded-client-cert") == {
          val cf = CertificateFactory.getInstance("X.509")
          val cert = cf.generateCertificate(new FileInputStream(upstreamServiceCert.cert))
          val digest = MessageDigest.getInstance("SHA-256")
          Some(s"""Hash=${printHexBinary(digest.digest(cert.getEncoded))};SAN=https://buoyant.io;Subject="C=US,CN=upstream"""")
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
}
