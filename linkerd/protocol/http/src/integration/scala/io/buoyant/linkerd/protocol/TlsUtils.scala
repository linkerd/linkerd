package io.buoyant.linkerd.protocol

import com.twitter.finagle.http.{Response, Request, TlsFilter}
import com.twitter.finagle.netty3.Netty3TransporterTLSConfig
import com.twitter.finagle.ssl.Ssl
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.NullTracer
import com.twitter.finagle.transport.Transport
import com.twitter.util.{Future, Var}
import java.io.{FileInputStream, File}
import java.net.{SocketAddress, InetSocketAddress}
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.{SSLContext, TrustManagerFactory}
import scala.sys.process._
import com.twitter.finagle.{Http => FinagleHttp, _}

object TlsUtils {

  /*
   * helpers
   */

  def run(p: ProcessBuilder): Int = p ! DevNull

  case class ServiceCert(cert: File, key: File)
  case class Certs(caCert: File, serviceCerts: Map[String, ServiceCert])
  def withCerts(names: String*)(f: Certs => Unit): Unit = {
    // First, we create a CA and get a cert/key for linker
    val tmpdir = new File("mktemp -d -t linkerd-tls".!!.stripLineEnd)
    try {
      val configFile = mkCaDirs (tmpdir)

      val caCert = new File (tmpdir, "cacert.pem")
      val caKey = new File (tmpdir, "private/cakey.pem")
      assert (run (newKeyAndCert ("/C=US/CN=Test CA", configFile, caKey, caCert) ) == 0)

      val svcCerts = names.map { name =>
        val routerReq = new File(tmpdir, s"${name}req.pem")
        val routerCert = new File(tmpdir, s"${name}cert.pem")
        val routerKey = new File(tmpdir, s"private/${name}key.pem")
        assert(
          run(
            newReq(
              s"/C=US/CN=$name",
              configFile,
              routerReq,
              routerKey
            )
          ) == 0
        )

        assert(
          run(
            signReq(
              configFile,
              caKey,
              caCert,
              routerReq,
              routerCert
            )
          ) == 0
        )
        // routerCert has the server's cert, signed by caCert
        name -> ServiceCert(routerCert, routerKey)
      }.toMap

      f(Certs (caCert, svcCerts) )
    } finally{
      val _ = Seq("rm", "-rf", tmpdir.getPath).!
    }
  }

  def upstreamTls(server: ListeningServer, tlsName: String, caCert: File) = {
    val address = Address(server.boundAddress.asInstanceOf[InetSocketAddress])

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
    val address = Address(server.boundAddress.asInstanceOf[InetSocketAddress])

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
    def mk(name: String)(f: Request => Response): Downstream = {
      val service = Service.mk { req: Request => Future(f(req)) }
      val server = FinagleHttp.server
        .configured(param.Label(name))
        .configured(param.Tracer(NullTracer))
        .serve(":*", service)
      Downstream(name, server)
    }

    def mkTls(name: String, cert: File, key: File)
      (f: Request => Response): Downstream = {
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

    def constTls(
      name: String,
      value: String,
      cert: File,
      key: File
    ): Downstream =
      mkTls(name, cert, key) { _ =>
        val rsp = Response()
        rsp.contentString = value
        rsp
      }
  }


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
