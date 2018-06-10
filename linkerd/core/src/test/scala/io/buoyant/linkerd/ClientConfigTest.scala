package io.buoyant.linkerd

import com.fasterxml.jackson.databind.JsonMappingException
import io.buoyant.config.Parser
import io.buoyant.test.FunSuite
import org.scalatest.Matchers._

class ClientConfigTest extends FunSuite {

  test("disableValidation: true incompatible with clientAuth") {
    val yaml =
      """
        |commonName: foo
        |disableValidation: true
        |clientAuth:
        |  certPath: cert.pem
        |  keyPath: key.pem
      """.stripMargin
    val exception =
      intercept[JsonMappingException] {
        Parser.objectMapper(yaml, Nil).readValue[TlsClientConfig](yaml)
      }
    assert(exception.getCause.isInstanceOf[IllegalArgumentException])
    assert(exception.getCause.getMessage ==
      "requirement failed: disableValidation: true is incompatible with clientAuth")
  }

  test("disableValidation: false compatible with clientAuth") {
    val yaml =
      """
        |commonName: foo
        |disableValidation: false
        |clientAuth:
        |  certPath: cert.pem
        |  keyPath: key.pem
      """.stripMargin
    Parser.objectMapper(yaml, Nil).readValue[TlsClientConfig](yaml)
  }

  test("should use configured trust bundle") {
    val yaml =
      """
        |commonName: foo
        |clientAuth:
        |  certPath: cert.pem
        |  keyPath: key.pem
        |trustCertsBundle: trust-certs.pem
      """.stripMargin
    val config = Parser.objectMapper(yaml, Nil).readValue[TlsClientConfig](yaml)
    config.trustCertsBundle.get should equal("trust-certs.pem")
  }

  test("should use configured trust certs") {
    val yaml =
      """
        |commonName: foo
        |clientAuth:
        |  certPath: cert.pem
        |  keyPath: key.pem
        |trustCerts:
        |  - trust-certs.pem
      """.stripMargin
    val config = Parser.objectMapper(yaml, Nil).readValue[TlsClientConfig](yaml)
    config.trustCerts.get should contain only "trust-certs.pem"
  }

  test("should parse tls protocols") {
    val yaml =
      """
        |commonName: foo
        |clientAuth:
        |  certPath: cert.pem
        |  keyPath: key.pem
        |protocols:
        |  - TLSv1.0
        |  - TLSv1.2
      """.stripMargin
    val config = Parser.objectMapper(yaml, Nil).readValue[TlsClientConfig](yaml)
    config.protocols.get should contain only ("TLSv1.0", "TLSv1.2")
  }

}
