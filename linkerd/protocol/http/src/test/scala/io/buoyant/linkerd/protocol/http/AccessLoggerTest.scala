package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.Service
import com.twitter.finagle.http._
import com.twitter.util.{Future, Promise, Time}
import com.twitter.logging.Logger
import io.buoyant.test.Awaits
import java.util.{logging => javalog}
import org.scalatest.FunSuite
import java.lang.{StringBuilder => JStringBuilder}

class AccessLoggerTest extends FunSuite with Awaits {

  object StringLogger extends Logger("string", javalog.Logger.getAnonymousLogger()) {
    val logged = new JStringBuilder(2048)

    override def info(msg: String, items: Any*) {
      logged.append(msg)
    }

    def getLoggedLines(): String = logged.toString()
  }

  test("access logger filter") {
    val done = new Promise[Unit]
    // This timestamp is: Wed, 06 Jan 2016 21:21:26 GMT
    Time.withTimeAt(Time.fromSeconds(1452115286)) { tc =>
      val service = AccessLogger(StringLogger) andThen Service.mk[Request, Response] { req =>
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

      val f = service(req)
      assert(StringLogger.getLoggedLines() ==
        """monkeys 0.0.0.0 - - [06/01/2016:21:21:26 +0000] "HEAD /foo?bar=bah HTTP/1.1" 402 304374 "-" "-"""")
    }
  }
}
