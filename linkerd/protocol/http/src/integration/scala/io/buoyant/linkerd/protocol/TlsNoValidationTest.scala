package io.buoyant.linkerd
package protocol

import com.twitter.conversions.DurationOps._
import com.twitter.finagle.http.Request
import io.buoyant.linkerd.tls.TlsUtils._
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class TlsNoValidationTest extends FunSuite with Awaits {

  test("tls router + plain upstream without validation") {
    withCerts("linkerd") { certs =>
      val dog = Downstream.constTls("dogs", "woof", certs.serviceCerts("linkerd").cert,
        certs.serviceCerts("linkerd").key)
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
             |  client:
             |    tls:
             |      disableValidation: true
             |""".stripMargin
        val init = Linker.Initializers(
          protocol = Seq(HttpInitializer)
        )
        val linker = init.load(linkerConfig)
        val router = linker.routers.head.initialize()
        try {
          val server = router.servers.head.serve()
          try {
            val client = Upstream.mk(server)
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
