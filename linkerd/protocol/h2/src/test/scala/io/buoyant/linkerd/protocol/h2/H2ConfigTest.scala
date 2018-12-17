package io.buoyant.linkerd.protocol
package h2

import com.twitter.conversions.DurationOps._
import com.twitter.finagle.Path
import com.twitter.finagle.buoyant.h2.param.FlowControl._
import com.twitter.finagle.buoyant.h2.param.Settings._
import com.twitter.finagle.netty4.ssl.server.Netty4ServerEngineFactory
import com.twitter.finagle.ssl.server.SslServerEngineFactory
import com.twitter.util.StorageUnit
import io.buoyant.config.Parser
import io.buoyant.router.h2.ClassifiedRetries.{BufferSize, ClassificationTimeout}
import io.buoyant.test.FunSuite

class H2ConfigTest extends FunSuite {

  def parse(yaml: String): H2Config = {
    val mapper = Parser.objectMapper(yaml, Iterable(Seq(H2Initializer)))
    mapper.readValue[H2Config](yaml)
  }

  test("parse config") {
    val yaml =
      s"""|protocol: h2
          |h2AccessLog: logs/access.log
          |h2AccessLogRollPolicy: daily
          |h2AccessLogAppend: true
          |h2AccessLogRotateCount: -1
          |client:
          |  windowUpdateRatio: 0.9
          |  headerTableBytes: 1024
          |  initialStreamWindowBytes: 524288
          |  maxFrameBytes: 8192
          |  maxHeaderListBytes: 1025
          |service:
          |  classificationTimeoutMs: 350
          |  retryBufferSize:
          |    requestBytes: 16384
          |    responseBytes: 16384
          |servers:
          |  - port: 5000
          |    windowUpdateRatio: 0.5
          |    headerTableBytes: 2048
          |    initialStreamWindowBytes: 1048576
          |    maxConcurrentStreamsPerConnection: 800
          |    maxFrameBytes: 16384
          |    maxHeaderListBytes: 2049
          |    tls:
          |      certPath: cert.pem
          |      keyPath: key.pem
          |      caCertPath: cacert.pem
          |      ciphers:
          |      - "foo"
          |      - "bar"
          |      requireClientAuth: true
          |""".stripMargin
    val config = parse(yaml)

    assert(config.h2AccessLog.get == "logs/access.log")
    assert(config.h2AccessLogRollPolicy.get == "daily")
    assert(config.h2AccessLogAppend.get)
    assert(config.h2AccessLogRotateCount.get == -1)

    val cparams = config.client.get.clientParams.paramsFor(Path.read("/foo"))
    assert(cparams[AutoRefillConnectionWindow] == AutoRefillConnectionWindow(true))
    assert(cparams[WindowUpdateRatio] == WindowUpdateRatio(0.9f))
    assert(cparams[HeaderTableSize] == HeaderTableSize(Some(StorageUnit.fromKilobytes(1))))
    assert(cparams[InitialStreamWindowSize] == InitialStreamWindowSize(Some(StorageUnit.fromKilobytes(512))))
    assert(cparams[MaxFrameSize] == MaxFrameSize(Some(StorageUnit.fromKilobytes(8))))
    assert(cparams[MaxHeaderListSize] == MaxHeaderListSize(Some(StorageUnit.fromBytes(1025))))

    val sparams = config.servers.head.serverParams
    assert(sparams[AutoRefillConnectionWindow] == AutoRefillConnectionWindow(true))
    assert(sparams[WindowUpdateRatio] == WindowUpdateRatio(0.5f))
    assert(sparams[HeaderTableSize] == HeaderTableSize(Some(StorageUnit.fromKilobytes(2))))
    assert(sparams[InitialStreamWindowSize] == InitialStreamWindowSize(Some(StorageUnit.fromMegabytes(1))))
    assert(sparams[MaxConcurrentStreams] == MaxConcurrentStreams(Some(800)))
    assert(sparams[MaxFrameSize] == MaxFrameSize(Some(StorageUnit.fromKilobytes(16))))
    assert(sparams[MaxHeaderListSize] == MaxHeaderListSize(Some(StorageUnit.fromBytes(2049))))
    assert(sparams[SslServerEngineFactory.Param].factory.isInstanceOf[Netty4ServerEngineFactory])

    val pparams = config.service.get.pathParams.paramsFor(Path.read("/foo"))
    assert(pparams[ClassificationTimeout] == ClassificationTimeout(350.milliseconds))
    assert(pparams[BufferSize] == BufferSize(16384, 16384))

    val tls = config.servers.head.tls.get
    assert(tls.certPath == "cert.pem")
    assert(tls.keyPath == "key.pem")
    assert(tls.caCertPath == Some("cacert.pem"))
    assert(tls.ciphers == Some(List("foo", "bar")))
    assert(tls.requireClientAuth == Some(true))
  }

}
