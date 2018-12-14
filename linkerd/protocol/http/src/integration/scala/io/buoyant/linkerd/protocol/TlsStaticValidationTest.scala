package io.buoyant.linkerd
package protocol

import com.twitter.finagle.http.{Status, Request}
import io.buoyant.linkerd.tls.TlsUtils._
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class TlsStaticValidationTest extends FunSuite with Awaits {

  val init = Linker.Initializers(
    protocol = Seq(HttpInitializer)
  )

  test("tls router + plain upstream with static validation and trustCertsBundle") {
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
             |      commonName: linkerd
             |      trustCertsBundle: ${certs.caCert.getPath}
             |""".stripMargin
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

  test("tls router + plain upstream with static validation and trustCerts") {
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
             |      commonName: linkerd
             |      trustCerts:
             |      - ${certs.caCert.getPath}
             |""".stripMargin
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

  test("tls router + plain upstream with static validation and incorrect common name") {
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
             |  service:
             |    retries:
             |      budget:
             |        minRetriesPerSec: 0
             |        percentCanRetry: 0.0
             |  client:
             |    tls:
             |      commonName: wrong
             |      trustCertsBundle: ${certs.caCert.getPath}
             |""".stripMargin
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
                assert(await(client(req)).status == Status.BadGateway)
              }
            } finally await(client.close())
          } finally await(server.close())
        } finally await(router.close())
      } finally await(dog.server.close())
    }
  }
}
