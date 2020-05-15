package io.buoyant.linkerd.tls

import java.io.{File, FileWriter}
import scala.collection.mutable
import scala.sys.process._

object TlsUtils {

  /*
   * helpers
   */

  def run(p: ProcessBuilder): Int = p ! DevNull

  case class ServiceCert(cert: File, key: File) {
    def delete() = { assert(cert.delete() && key.delete()) }

    def renameTo(name: String) = {
      val newCert = new File(cert.getParentFile, s"${name}_cert.pem")
      val newKey = new File(key.getParentFile, s"${name}_pk8.pem")
      assert(cert.renameTo(newCert) && key.renameTo(newKey))
    }

  }
  case class Certs(caCert: File, serviceCerts: Map[String, ServiceCert])

  def withCerts(names: String*)(f: Certs => Unit): Unit = {
    // By default add DNS SAN entries for upstream/downstream certificates.
    // Important: DNS SAN entries overrides CN as per RFC 6125. So, if adding CN specific logic, use the method {@link #withCertsWithCustomDnsAltNames}
    // Reference: https://stackoverflow.com/questions/5935369/ssl-how-do-common-names-cn-and-subject-alternative-names-san-work-together
    withCertsWithCustomDnsAltNames(names, names)(f: Certs => Unit)
  }

  def withCertsWithCustomDnsAltNames(names: Seq[String], dnsAltNames: Seq[String])(f: Certs => Unit): Unit = {
    // First, we create a CA and get a cert/key for linker
    val tmpdir = new File("mktemp -d -t linkerd-tls.XXXXXX".!!.stripLineEnd)
    try {
      val configFile = mkCaDirs(tmpdir)
      if (dnsAltNames != null) {
        // More granular way of doing this would be to use -addext option in openssl command once it's available.
        // Reference: https://github.com/openssl/openssl/commit/bfa470a4f64313651a35571883e235d3335054eb
        addDnsAltNamesInConfig(tmpdir, dnsAltNames)
      }

      val caCert = new File(tmpdir, "ca+cert.pem")
      val caKey = new File(tmpdir, "private/ca_key.pem")
      assertOk(newKeyAndCert("/C=US/CN=Test CA", configFile, caKey, caCert))

      val svcCerts = names.map { name =>
        val routerReq = new File(tmpdir, s"${name}_req.pem")
        val routerCert = new File(tmpdir, s"${name}_cert.pem")
        val routerKey = new File(tmpdir, s"private/${name}_key.tmp.pem")
        val routerPk8 = new File(tmpdir, s"private/${name}_pk8.pem")

        assertOk(newReq(s"/C=US/CN=$name", configFile, routerReq, routerKey))
        assertOk(signReq(configFile, caKey, caCert, routerReq, routerCert))
        assertOk(toPk8(routerKey, routerPk8))

        // routerCert has the server's cert, signed by caCert
        name -> ServiceCert(routerCert, routerPk8)
      }.toMap

      f(Certs(caCert, svcCerts))
    } finally {
      val _ = Seq("rm", "-rf", tmpdir.getPath).!
    }
  }

  def assertOk(cmd: ProcessBuilder): Unit =
    assert(run(cmd) == 0, s"`$cmd` failed")

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

  def addDnsAltNamesInConfig(dir: File, names: Seq[String]): Unit = {
    val configFile = new File(dir, "openssl.cfg")
    val cw = new FileWriter(configFile, true)
    cw.write(dnsAltNames(names))
    cw.close()
  }

  def dnsAltNames(names: Seq[String]): String = {
    val altNames = names.zipWithIndex.map { case (name, i) => s"DNS.${i + 1} = $name" }
    altNames.mkString("\n")
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
    |x509_extensions = x509_extensions
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
    |encrypt_key = no
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
    |
    |[ x509_extensions ]
    |subjectAltName = @alt_names
    |
    |[alt_names]
    |URI.1 = https://buoyant.io
    |IP.1 = 127.0.0.1
    |""".stripMargin

  def newKeyAndCert(subj: String, cfg: File, key: File, cert: File): ProcessBuilder =
    Seq("openssl", "req", "-x509", "-nodes", "-newkey", "rsa:2048",
      "-config", cfg.getPath,
      "-subj", subj,
      "-keyout", key.getPath,
      "-out", cert.getPath)

  def newReq(subj: String, cfg: File, req: File, key: File): ProcessBuilder =
    Seq("openssl", "req", "-new", "-nodes",
      "-config", cfg.getPath,
      "-subj", subj,
      "-keyout", key.getPath,
      "-out", req.getPath)

  def signReq(cfg: File, key: File, cert: File, req: File, newCert: File): ProcessBuilder =
    Seq("openssl", "ca", "-batch",
      "-config", cfg.getPath,
      "-keyfile", key.getPath,
      "-cert", cert.getPath,
      "-out", newCert.getPath,
      "-infiles", req.getPath)

  def toPk8(in: File, out: File): ProcessBuilder =
    Seq("openssl", "pkcs8", "-topk8", "-nocrypt", "-in", in.getPath, "-out", out.getPath)
}
