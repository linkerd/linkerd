package io.buoyant.linkerd
package protocol

import com.twitter.conversions.time._
import com.twitter.finagle.transport.Transport
import com.twitter.finagle.{Http => FinagleHttp, _}
import com.twitter.finagle.http._
import com.twitter.finagle.netty3.Netty3TransporterTLSConfig
import com.twitter.finagle.ssl.Ssl
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.{BufferingTracer, NullTracer}
import com.twitter.util._
import io.buoyant.router.Http
import io.buoyant.test.Awaits
import io.l5d.clientTls.{noValidation, static}
import java.io.{File, FileInputStream}
import java.net.{InetAddress, InetSocketAddress, SocketAddress}
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl._
import org.scalatest.FunSuite
import scala.sys.process._

class HttpsIntegrationTest extends FunSuite with Awaits {
  import TlsUtils._

  override val defaultWait = 2.seconds

  test("tls server + plain backend") {
    withCerts { certs =>
      val dog = Downstream.const("dogs", "woof")
      try {
        val linkerConfig =
          s"""
             |routers:
             |- protocol: http
             |  baseDtab: |
             |    /p/dog => /$$/inet/127.1/${dog.port} ;
             |    /http/1.1/GET/clifford => /p/dog ;
             |  servers:
             |  - port: 0
             |    tls:
             |      certPath: ${certs.routerCert.getPath}
             |      keyPath: ${certs.routerKey.getPath}
             |""".
            stripMargin
        val protocols = ProtocolInitializers(new HttpInitializer)
        val linker = Linker.mk(protocols, NamerInitializers.empty, TlsClientInitializers.empty)
          .read(Yaml(linkerConfig))

        val router = linker.routers.head.initialize()
        try {
          val server = router.servers.head.serve()
          try {
            val client = upstreamTls(server, "linkerd", certs.caCert)
            try {
              val rsp = {
                val req = Request()
                req.host = "clifford"
                await(client(req))
              }
              assert(rsp.contentString == "woof")

            } finally (await(client.close()))
          } finally (await(server.close()))
        } finally (await(router.close()))
      } finally (await(dog.server.close()))
    }
  }

  test("tls router + plain upstream without validation") {
    withCerts { certs =>
      val dog = Downstream.constTls("dogs", "woof", certs.routerCert, certs.routerKey)
      try {
        val linkerConfig =
          s"""
             |routers:
             |- protocol: http
             |  baseDtab: |
             |    /p/dog => /$$/inet/127.1/${dog.port} ;
             |    /http/1.1/GET/clifford => /p/dog ;
             |  servers:
             |  - port: 0
             |  tls:
             |    kind: io.l5d.clientTls.noValidation
             |""".
            stripMargin
        val protocols = ProtocolInitializers(new HttpInitializer)
        val tls = TlsClientInitializers(new noValidation)
        val linker = Linker.mk(protocols, NamerInitializers.empty, tls)
          .read(Yaml(linkerConfig))
        val router = linker.routers.head.initialize()
        try {
          val server = router.servers.head.serve()
          try {
            val client = upstream(server)
            try {
              val rsp = {
                val req = Request()
                req.host = "clifford"
                await(client(req))
              }
              assert(rsp.contentString == "woof")

            } finally (await(client.close()))
          } finally (await(server.close()))
        } finally (await(router.close()))
      } finally (await(dog.server.close()))
    }
  }

  test("tls router + plain upstream with static validation") {
    withCerts { certs =>
      val dog = Downstream.constTls("dogs", "woof", certs.routerCert, certs.routerKey)
      try {
        val linkerConfig =
          s"""
             |routers:
             |- protocol: http
             |  baseDtab: |
             |    /p/dog => /$$/inet/127.1/${dog.port} ;
             |    /http/1.1/GET/clifford => /p/dog ;
             |  servers:
             |  - port: 0
             |  tls:
             |    kind: io.l5d.clientTls.static
             |    commonName: linkerd
             |    caCertPath: ${certs.caCert.getPath}
             |""".
            stripMargin
        val protocols = ProtocolInitializers(new HttpInitializer)
        val tls = TlsClientInitializers(new static)
        val linker = Linker.mk(protocols, NamerInitializers.empty, tls)
          .read(Yaml(linkerConfig))
        val router = linker.routers.head.initialize()
        try {
          val server = router.servers.head.serve()
          try {
            val client = upstream(server)
            try {
              val rsp = {
                val req = Request()
                req.host = "clifford"
                await(client(req))
              }
              assert(rsp.contentString == "woof")

            } finally (await(client.close()))
          } finally (await(server.close()))
        } finally (await(router.close()))
      } finally (await(dog.server.close()))
    }
  }

  test("tls router + plain upstream with static validation and incorrect common name") {
    withCerts { certs =>
      val dog = Downstream.constTls("dogs", "woof", certs.routerCert, certs.routerKey)
      try {
        val linkerConfig =
          s"""
             |routers:
             |- protocol: http
             |  baseDtab: |
             |    /p/dog => /$$/inet/127.1/${dog.port} ;
             |    /http/1.1/GET/clifford => /p/dog ;
             |  servers:
             |  - port: 0
             |  tls:
             |    kind: io.l5d.clientTls.static
             |    commonName: wrong
             |    caCertPath: ${certs.caCert.getPath}
             |""".
            stripMargin
        val protocols = ProtocolInitializers(new HttpInitializer)
        val tls = TlsClientInitializers(new static)
        val linker = Linker.mk(protocols, NamerInitializers.empty, tls)
          .read(Yaml(linkerConfig))
        val router = linker.routers.head.initialize()
        try {
          val server = router.servers.head.serve()
          try {
            val client = upstream(server)
            try {
              val rsp = {
                val req = Request()
                req.host = "clifford"
                intercept[Failure](await(client(req)))
              }
            } finally (await(client.close()))
          } finally (await(server.close()))
        } finally (await(router.close()))
      } finally (await(dog.server.close()))
    }
  }


  /*
   * helpers
   */

  def run(p: ProcessBuilder): Int = debug(p) ! DevNull
  def debug(p: ProcessBuilder): ProcessBuilder = {
    //println(p.toString)
    p
  }

  case class Certs(caCert: File, routerCert: File, routerKey: File)
  def withCerts(f: Certs => Unit): Unit = {
    // First, we create a CA and get a cert/key for linker
    val tmpdir = new File("mktemp -d -t linkerd-tls".!!.stripLineEnd)
    try {
      val configFile = mkCaDirs (tmpdir)

      val caCert = new File (tmpdir, "cacert.pem")
      val caKey = new File (tmpdir, "private/cakey.pem")
      assert (run (newKeyAndCert ("/C=US/CN=Test CA", configFile, caKey, caCert) ) == 0)

      val routerReq = new File (tmpdir, "routerreq.pem")
      val routerCert = new File (tmpdir, "routercert.pem")
      val routerKey = new File (tmpdir, "private/routerkey.pem")
      assert (run (newReq ("/C=US/CN=linkerd", configFile, routerReq, routerKey) ) == 0)

      assert (run (signReq (configFile, caKey, caCert, routerReq, routerCert) ) == 0)
      // routerCert has the server's cert, signed by caCert

      f (Certs (caCert, routerCert, routerKey) )
    } finally(Seq("echo", "rm", "-rf", tmpdir.getPath).!)
  }

  def upstreamTls(server: ListeningServer, tlsName: String, caCert: File) = {
    val address = server.boundAddress.asInstanceOf[InetSocketAddress]

    // Establish an SSL context that uses our generated certificate.
    // Cribbed from http://stackoverflow.com/questions/18513792
    val cf = CertificateFactory.getInstance("X.509");
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    val ks = KeyStore.getInstance(KeyStore.getDefaultType())
    ks.load(null)
    ks.setCertificateEntry("caCert", cf.generateCertificate(new FileInputStream(caCert)))
    tmf.init(ks)
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, tmf.getTrustManagers(), null)
    def tls(address: SocketAddress) = address match {
      case addr: InetSocketAddress => Ssl.client(ctx, addr.getAddress.getHostAddress, addr.getPort)
      case _ => Ssl.client()
    }

    val name = Name.Bound(Var.value(Addr.Bound(address)), address)
    FinagleHttp.client
      .configured(param.Stats(NullStatsReceiver))
      .configured(param.Tracer(NullTracer))
      .withTls(new Netty3TransporterTLSConfig(tls, Some(tlsName)))
      .transformed(_.remove(TlsFilter.role)) // do NOT rewrite Host headers using tlsName
      .newClient(name, "upstream").toService
  }

  def upstream(server: ListeningServer) = {
    val address = server.boundAddress.asInstanceOf[InetSocketAddress]

    val name = Name.Bound(Var.value(Addr.Bound(address)), address)
    FinagleHttp.client
      .configured(param.Stats(NullStatsReceiver))
      .configured(param.Tracer(NullTracer))
      .transformed(_.remove(TlsFilter.role)) // do NOT rewrite Host headers using tlsName
      .newClient(name, "upstream").toService
  }

  case class Downstream(name: String, server: ListeningServer) {
    val address = server.boundAddress.asInstanceOf[InetSocketAddress]
    val port = address.getPort
    val dentry = Dentry(
      Path.read(s"/s/$name"),
      NameTree.read(s"/$$/inet/127.1/$port")
    )
  }

  object Downstream {
    def mk(name: String)(f: Request=>Response): Downstream = {
      val service = Service.mk { req: Request => Future(f(req)) }
      val server = FinagleHttp.server
        .configured(param.Label(name))
        .configured(param.Tracer(NullTracer))
        .serve(":*", service)
      Downstream(name, server)
    }

    def mkTls(name: String, cert: File, key: File)(f: Request=>Response): Downstream = {
      val service = Service.mk { req: Request => Future(f(req)) }
      val server = FinagleHttp.server
        .configured(param.Label(name))
        .configured(param.Tracer(NullTracer))
        .configured(
          Transport.TLSServerEngine(
            Some(() => Ssl.server(cert.getPath, key.getPath, null, null, null))
          )
        )
        .serve(":*", service)
      Downstream(name, server)
    }

    def const(name: String, value: String): Downstream =
      mk(name) { _ =>
        val rsp = Response()
        rsp.contentString = value
        rsp
      }

    def constTls(name: String, value: String, cert: File, key: File): Downstream =
      mkTls(name, cert, key) { _ =>
        val rsp = Response()
        rsp.contentString = value
        rsp
      }
  }

}

object TlsUtils {
  val DevNull = ProcessLogger(_ => ())

  def mkCaDirs(dir: File): File = {
    new File(dir, "newcerts").mkdir()
    new File(dir, "private").mkdir()
    new File(dir, "index.txt").createNewFile()
    val serial = new java.io.PrintWriter(new File(dir, "serial"))
    serial.println("01")
    serial.close()

    val configFile = new File(dir, "openssl.cfg")
    val cw = new java.io.PrintWriter(configFile)
    cw.print(opensslCfg(dir.getPath))
    cw.close()
    configFile
  }

  // copied from http://www.eclectica.ca/howto/ssl-cert-howto.php
  def opensslCfg(dir: String) = s"""
    |dir = $dir
    |
    |[ ca ]
    |default_ca = CA_default
    |
    |[ CA_default ]
    |serial = $$dir/serial
    |database = $$dir/index.txt
    |new_certs_dir = $$dir/newcerts
    |certificate  = $$dir/cacert.pem
    |private_key = $$dir/private/cakey.pem
    |default_days = 1
    |default_md  = sha256
    |preserve = no
    |email_in_dn  = no
    |nameopt = default_ca
    |certopt = default_ca
    |policy = policy_match
    |
    |[ policy_match ]
    |commonName = supplied
    |countryName = optional
    |stateOrProvinceName = optional
    |organizationName = optional
    |organizationalUnitName = optional
    |emailAddress = optional
    |
    |[ req ]
    |default_bits = 2048
    |default_keyfile = priv.pem
    |default_md = sha256
    |distinguished_name = req_distinguished_name
    |req_extensions = v3_req
    |encyrpt_key = no
    |
    |[ req_distinguished_name ]
    |
    |[ v3_ca ]
    |basicConstraints = CA:TRUE
    |subjectKeyIdentifier = hash
    |authorityKeyIdentifier = keyid:always,issuer:always
    |
    |[ v3_req ]
    |basicConstraints = CA:FALSE
    |subjectKeyIdentifier = hash
    |""".stripMargin

  def newKeyAndCert(subj: String, cfg: File, key: File, cert: File): ProcessBuilder =
    Seq("openssl", "req", "-x509", "-nodes", "-newkey", "rsa:2048",
      "-config", cfg.getPath,
      "-subj",   subj,
      "-keyout", key.getPath,
      "-out",    cert.getPath
    )

  def newReq(subj: String, cfg: File, req: File, key: File): ProcessBuilder  =
    Seq("openssl", "req", "-new", "-nodes",
      "-config", cfg.getPath,
      "-subj",   subj,
      "-keyout", key.getPath,
      "-out",    req.getPath
    )

  def signReq(cfg: File, key: File, cert: File, req: File, newCert: File): ProcessBuilder  =
    Seq("openssl", "ca", "-batch",
      "-config",  cfg.getPath,
      "-keyfile", key.getPath,
      "-cert",    cert.getPath,
      "-out",     newCert.getPath,
      "-infiles", req.getPath
    )

}
