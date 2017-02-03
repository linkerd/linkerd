package io.buoyant.linkerd.protocol
package h2

import com.twitter.conversions.storage._
import com.twitter.finagle.Stack
import com.twitter.finagle.buoyant.h2.param.FlowControl._
import com.twitter.finagle.buoyant.h2.param.Settings._
import io.buoyant.config.Parser
import io.buoyant.linkerd.RouterConfig
import io.buoyant.test.FunSuite

class H2ConfigTest extends FunSuite {

  def parse(yaml: String): H2Config = {
    val mapper = Parser.objectMapper(yaml, Iterable(Seq(H2Initializer)))
    mapper.readValue[H2Config](yaml)
  }

  test("parse config") {
    val yaml =
      s"""|protocol: h2
          |client:
          |  connectionFlowControl: true
          |  windowUpdateRatio: 0.9
          |  headerTableBytes: 1024
          |  initialWindowBytes: 524288
          |  maxConcurrentStreamsPerConnection: 15
          |  maxFrameBytes: 8192
          |  maxHeaderListBytes: 1025
          |servers:
          |  - port: 5000
          |    connectionFlowControl: false
          |    windowUpdateRatio: 0.5
          |    headerTableBytes: 2048
          |    initialWindowBytes: 1048576
          |    maxConcurrentStreamsPerConnection: 8
          |    maxFrameBytes: 16384
          |    maxHeaderListBytes: 2049
          |""".stripMargin
    val config = parse(yaml)

    val cparams = config.client.get.withParams(Stack.Params.empty)
    assert(cparams[AutoRefillConnectionWindow] == AutoRefillConnectionWindow(false))
    assert(cparams[WindowUpdateRatio] == WindowUpdateRatio(0.9f))
    assert(cparams[HeaderTableSize] == HeaderTableSize(Some(1.kilobyte)))
    assert(cparams[InitialWindowSize] == InitialWindowSize(Some(512.kilobytes)))
    assert(cparams[MaxConcurrentStreams] == MaxConcurrentStreams(Some(15)))
    assert(cparams[MaxFrameSize] == MaxFrameSize(Some(8.kilobytes)))
    assert(cparams[MaxHeaderListSize] == MaxHeaderListSize(Some(1025.bytes)))

    val sparams = config.servers.head.withParams(Stack.Params.empty)
    assert(sparams[AutoRefillConnectionWindow] == AutoRefillConnectionWindow(true))
    assert(sparams[WindowUpdateRatio] == WindowUpdateRatio(0.5f))
    assert(sparams[HeaderTableSize] == HeaderTableSize(Some(2.kilobytes)))
    assert(sparams[InitialWindowSize] == InitialWindowSize(Some(1.megabyte)))
    assert(sparams[MaxConcurrentStreams] == MaxConcurrentStreams(Some(8)))
    assert(sparams[MaxFrameSize] == MaxFrameSize(Some(16.kilobytes)))
    assert(sparams[MaxHeaderListSize] == MaxHeaderListSize(Some(2049.bytes)))
  }

}
