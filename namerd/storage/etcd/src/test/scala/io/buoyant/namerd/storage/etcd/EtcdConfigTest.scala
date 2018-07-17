package io.buoyant.namerd.storage.etcd

import com.twitter.finagle.{Path, Stack}
import io.buoyant.config.Parser
import io.buoyant.config.types.Port
import io.buoyant.namerd.DtabStoreConfig
import org.scalatest.{FunSuite, OptionValues}

class EtcdConfigTest extends FunSuite with OptionValues {

  def parsedEtcdConfig(yaml: String) = {
    val mapper = Parser.objectMapper(yaml, Iterable(Seq(EtcdDtabStoreInitializer)))
    mapper.readValue[DtabStoreConfig](yaml).asInstanceOf[EtcdConfig]
  }
  test("sanity") {
    val store = EtcdConfig(None, None, Some(Path.read("/foo/bar")), None).mkDtabStore(Stack.Params.empty)
  }

  test("parse config") {
    val yaml =
      """|kind: io.l5d.etcd
         |experimental: true
         |pathPrefix: /foo/bar
         |host: etcd.dentist
         |port: 80
      """.stripMargin
    val etcd = parsedEtcdConfig(yaml)
    assert(etcd.host.value == "etcd.dentist")
    assert(etcd.port.value == Port(80))
    assert(etcd.pathPrefix == Some(Path.read("/foo/bar")))
  }

  test("parse tls config"){
    val yaml =
      """|kind: io.l5d.etcd
         |experimental: true
         |pathPrefix: /foo/bar
         |host: etcd.dentist
         |port: 80
         |tls:
         |  disableValidation: false
         |  commonName: "{service}"
         |  trustCerts:
         |  - /foo/caCert.pem
         |  clientAuth:
         |    certPath: /etcd-cert.pem
         |    keyPath: /etcd-key.pk8
      """.stripMargin

    parsedEtcdConfig(yaml).tls.foreach { etcdTLS =>
      assert(etcdTLS.disableValidation == Some(false))
      assert(etcdTLS.commonName == Some("{service}"))
      assert(etcdTLS.trustCerts == Some(List("/foo/caCert.pem")))
      assert(etcdTLS.clientAuth.get.certPath == "/etcd-cert.pem")
      assert(etcdTLS.clientAuth.get.keyPath == "/etcd-key.pk8")
    }

  }

}
