package io.buoyant.linkerd.protocol

import com.twitter.finagle.Service
import com.twitter.finagle.http._
import com.twitter.util.Future
import com.twitter.util.StdBenchAnnotations
import com.twitter.logging.NullLogger
import org.openjdk.jmh.annotations._
import io.buoyant.linkerd.protocol.http.AccessLogger
import io.buoyant.test.Awaits

// ./sbt 'project linkerd-protocol-benchmark' 'jmh:run -i 20 -prof gc .*AccessLoggerBenchmark.*'
@State(Scope.Benchmark)
class AccessLoggerBenchmark extends StdBenchAnnotations with Awaits {
  val service = AccessLogger(NullLogger) andThen Service.mk[Request, Response] { req =>
    val rsp = Response()
    rsp.status = Status.PaymentRequired
    rsp.contentType = "application/json"
    rsp.contentLength = 304374
    Future.value(rsp)
  }

  val req = Request()
  req.method = Method.Head
  req.uri = "/foo?bar=bah"
  req.host = "monkeys"
  req.contentType = "text/plain"

  @Benchmark
  def benchmark(): Response = {
    await(service(req))
  }
}

