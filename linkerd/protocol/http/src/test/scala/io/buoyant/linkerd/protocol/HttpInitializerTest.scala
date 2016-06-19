package io.buoyant.linkerd.protocol

import com.twitter.finagle.{Service, ServiceFactory, Stack}
import com.twitter.finagle.http.{Request, Response, Status, Version}
import com.twitter.finagle.stack.nilStack
import com.twitter.io.Reader
import com.twitter.util.{Future, Promise, Time}
import io.buoyant.test.Awaits
import org.scalatest.FunSuite
import org.scalatest.concurrent.Eventually
import scala.language.reflectiveCalls

class HttpInitializerTest extends FunSuite with Awaits with Eventually {

  test("path stack: services are not closed until streams are complete") {
    // Build a path stack that is controllable with promises (for only
    // one request).
    val serviceP, responseP, bodyP, respondingP, closedP = Promise[Unit]
    val http = new HttpInitializer {
      val svc = new Service[Request, Response] {
        def apply(req: Request) = {
          val rw = Reader.writable()
          val rsp = Response(req.version, Status.Ok, rw)
          val _ = bodyP.before(rw.close())
          respondingP.setDone()
          responseP.before(Future.value(rsp))
        }

        override def close(d: Time) = {
          closedP.setDone()
          Future.Unit
        }
      }
      val sf = ServiceFactory { () => serviceP.before(Future.value(svc)) }

      def make(params: Stack.Params = Stack.Params.empty) =
        (pathStack ++ Stack.Leaf(Stack.Role("leaf"), sf)).make(params)
    }

    // The factory is returned immediately because it is wrapped in a
    // FactoryToService.
    val factory = http.make()
    val svcf = factory()
    assert(svcf.isDefined)
    val svc = await(svcf)

    // When a request is processed, first the service must be acquired
    // from the service factroy, and then the response must be
    // returned from the service.
    val rspf = svc(Request())
    assert(!rspf.isDefined)
    assert(!respondingP.isDefined)

    serviceP.setDone()
    eventually { assert(respondingP.isDefined) }
    assert(!rspf.isDefined)

    responseP.setDone()
    eventually { assert(rspf.isDefined) }

    // Once the response is returned, FactoryToService tries to close
    // the service factory. Ensure that the service is not closed
    // until the response body is completely sent.
    val rsp = await(rspf)
    assert(rsp.isChunked)
    assert(!closedP.isDefined)

    // When the response body is written, it must be fully read from
    // response before the service will be closed.
    bodyP.setDone()
    assert(!closedP.isDefined)

    assert(await(rsp.reader.read(1)) == None)
    eventually { assert(closedP.isDefined) }
  }
}
