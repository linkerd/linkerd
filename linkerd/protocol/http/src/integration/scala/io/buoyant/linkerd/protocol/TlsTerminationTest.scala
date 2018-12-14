package io.buoyant.linkerd
package protocol

import com.twitter.conversions.DurationOps._
import com.twitter.finagle.http.Request
import io.buoyant.linkerd.tls.TlsUtils._
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class TlsTerminationTest extends FunSuite with Awaits {

  test("tls server + plain backend") {
    withCerts("linkerd") { certs =>
      val dog = Downstream.const("dogs", "woof")
      try {
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
             |""".stripMargin
        val linker = Linker.Initializers(Seq(HttpInitializer)).load(linkerConfig)

        val router = linker.routers.head.initialize()
        try {
          val server = router.servers.head.serve()
          try {
            val client = Upstream.mkTls(server, "linkerd", certs.caCert)
            try {
              val rsp = {
                val req = Request()
                req.host = "clifford"
                await(client(req))
              }
              assert(rsp.contentString == "woof")
              ()
            } finally await(client.close())
          } finally await(server.close())
        } finally await(router.close())
      } finally await(dog.server.close())
    }
  }
}
