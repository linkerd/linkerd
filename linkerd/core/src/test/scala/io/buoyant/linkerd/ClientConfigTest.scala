package io.buoyant.linkerd

import com.fasterxml.jackson.databind.JsonMappingException
import io.buoyant.config.Parser
import io.buoyant.test.FunSuite

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

}
