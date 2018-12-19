package io.buoyant.linkerd
package protocol

import com.twitter.conversions.DurationOps._
import com.twitter.finagle.http.{Status, Request, Response}
import com.twitter.finagle.{Failure, Service}
import io.buoyant.linkerd.tls.TlsUtils._
import io.buoyant.namer.fs.FsInitializer
import io.buoyant.test.Awaits
import java.io.File
import org.scalatest.FunSuite
import scala.sys.process._

class TlsBoundPathTest extends FunSuite with Awaits {

  test("tls router + 2 tls backends") {

    withCerts("bill.buoyant.io", "ted.buoyant.io") { certs =>
      val bill = Downstream
        .constTls(
          "bill",
          "whoa",
          certs.serviceCerts("bill.buoyant.io").cert,
          certs.serviceCerts("bill.buoyant.io").key
        )
      val ted = Downstream
        .constTls(
          "ted",
          "dude",
          certs.serviceCerts("ted.buoyant.io").cert,
          certs.serviceCerts("ted.buoyant.io").key
        )
      try {
        withDisco(bill, ted) { disco =>

          val linkerConfig = s"""
             |namers:
             |- kind: io.l5d.fs
             |  rootDir: ${disco.getPath}
             |
             |routers:
             |- protocol: http
             |  dtab: |
             |    /svc => /srv ;
             |    /srv => /#/io.l5d.fs
             |
             |  servers:
             |  - port: 0
             |  client:
             |    kind: io.l5d.static
             |    configs:
             |    - prefix: "/#/io.l5d.fs/{host}"
             |      tls:
             |        commonName: "{host}.buoyant.io"
             |        trustCertsBundle: ${certs.caCert.getPath}
             |""".
              stripMargin
          withLinkerdClient(linkerConfig) { client =>
            val billRsp = {
              val req = Request()
              req.host = "bill"
              await(client(req))
            }
            assert(billRsp.contentString == "whoa")

            val tedRsp = {
              val req = Request()
              req.host = "ted"
              await(client(req))
            }
            assert(tedRsp.contentString == "dude")
            ()
          }
        }
      } finally {
        await(bill.server.close().join(ted.server.close()).unit)
      }
    }
  }

  test("mix of tls and plain backends") {

    withCerts("bill.buoyant.io") { certs =>
      val bill = Downstream
        .constTls(
          "bill",
          "whoa",
          certs.serviceCerts("bill.buoyant.io").cert,
          certs.serviceCerts("bill.buoyant.io").key
        )
      val ted = Downstream
        .const("ted", "dude")

      try {
        withDisco(bill, ted) { disco =>

          val linkerConfig = s"""
            |namers:
            |- kind: io.l5d.fs
            |  rootDir: ${disco.getPath}
            |
            |routers:
            |- protocol: http
            |  dtab: |
            |    /svc => /#/io.l5d.fs;
            |
            |  servers:
            |  - port: 0
            |  client:
            |    kind: io.l5d.static
            |    configs:
            |    - prefix: "/#/io.l5d.fs/bill"
            |      tls:
            |        commonName: "bill.buoyant.io"
            |        trustCertsBundle: ${certs.caCert.getPath}
            |""".
            stripMargin
          withLinkerdClient(linkerConfig) { client =>
            val billRsp = {
              val req = Request()
              req.host = "bill"
              await(client(req))
            }
            assert(billRsp.contentString == "whoa")

            val tedRsp = {
              val req = Request()
              req.host = "ted"
              await(client(req))
            }
            assert(tedRsp.contentString == "dude")
            ()
          }
        }
      } finally {
        await(bill.server.close().join(ted.server.close()).unit)
      }
    }
  }

  test("multiple name patterns") {

    withCerts("excellent", "righteous") { certs =>
      val bill = Downstream
        .constTls(
          "bill",
          "whoa",
          certs.serviceCerts("excellent").cert,
          certs.serviceCerts("excellent").key
        )
      val ted = Downstream
        .constTls(
          "ted",
          "dude",
          certs.serviceCerts("righteous").cert,
          certs.serviceCerts("righteous").key
        )

      try {
        withDisco(bill, ted) { disco =>

          val linkerConfig = s"""
            |namers:
            |- kind: io.l5d.fs
            |  rootDir: ${disco.getPath}
            |
            |routers:
            |- protocol: http
            |  dtab: |
            |    /svc => /#/io.l5d.fs ;
            |
            |  servers:
            |  - port: 0
            |  client:
            |    kind: io.l5d.static
            |    configs:
            |    - prefix: "/#/io.l5d.fs/bill"
            |      tls:
            |        commonName: excellent
            |        trustCertsBundle: ${certs.caCert.getPath}
            |    - prefix: "/#/io.l5d.fs/ted"
            |      tls:
            |        commonName: righteous
            |        trustCertsBundle: ${certs.caCert.getPath}
            |""".
            stripMargin
          withLinkerdClient(linkerConfig) { client =>
            val billRsp = {
              val req = Request()
              req.host = "bill"
              await(client(req))
            }
            assert(billRsp.contentString == "whoa")

            val tedRsp = {
              val req = Request()
              req.host = "ted"
              await(client(req))
            }
            assert(tedRsp.contentString == "dude")
            ()
          }
        }
      } finally {
        await(bill.server.close().join(ted.server.close()).unit)
      }
    }
  }

  test("wrong common name") {
    // Create certificates without SAN DNS entries that override CN.
    withCertsWithCustomDnsAltNames(Seq("bill.buoyant.io", "ted.buoyant.io"), null) { certs =>
      val bill = Downstream
        .constTls(
          "bill",
          "whoa",
          certs.serviceCerts("ted.buoyant.io").cert,
          certs.serviceCerts("ted.buoyant.io").key
        )
      val ted = Downstream
        .constTls(
          "ted",
          "dude",
          certs.serviceCerts("bill.buoyant.io").cert,
          certs.serviceCerts("bill.buoyant.io").key
        )

      try {
        withDisco(bill, ted) { disco =>

          val linkerConfig = s"""
             |namers:
             |- kind: io.l5d.fs
             |  rootDir: ${disco.getPath}
             |
             |routers:
             |- protocol: http
             |  dtab: |
             |    /svc => /#/io.l5d.fs ;
             |
             |  servers:
             |  - port: 0
             |  service:
             |    retries:
             |      budget:
             |        minRetriesPerSec: 0
             |        percentCanRetry: 0.0
             |  client:
             |    kind: io.l5d.static
             |    configs:
             |    - prefix: "/#/io.l5d.fs/{host}"
             |      tls:
             |        commonName: "{host}.buoyant.io"
             |        trustCertsBundle: ${certs.caCert.getPath}
             |""".stripMargin
          withLinkerdClient(linkerConfig) { client =>
            val billRsp = {
              val req = Request()
              req.host = "bill"
              assert(await(client(req)).status == Status.BadGateway)
            }

            val tedRsp = {
              val req = Request()
              req.host = "ted"
              assert(await(client(req)).status == Status.BadGateway)
            }
            ()
          }
        }
      } finally {
        await(bill.server.close().join(ted.server.close()).unit)
      }
    }
  }

  private[this] def withDisco(downstreams: Downstream*)(f: File => Unit): Unit = {
    val disco = new File("mktemp -d -t disco.XXXXX".!!.stripLineEnd)
    try {
      for (ds <- downstreams) {
        val w = new java.io.PrintWriter(new File(disco, ds.name))
        w.println(s"127.1 ${ds.port}")
        w.close()
      }
      f(disco)
    } finally {
      val _ = Seq("echo", "rm", "-rf", disco.getPath).!
    }
  }

  private[this] def withLinkerdClient(config: String)(f: Service[Request, Response] => Unit): Unit = {
    val init = Linker.Initializers(
      protocol = Seq(HttpInitializer),
      namer = Seq(FsInitializer)
    )
    val linker = init.load(config)
    val router = linker.routers.head.initialize()
    try {
      val server = router.servers.head.serve()
      try {
        val client = Upstream.mk(server)
        try {
          f(client)
        } finally await(client.close())
      } finally await(server.close())
    } finally await(router.close())
  }
}
