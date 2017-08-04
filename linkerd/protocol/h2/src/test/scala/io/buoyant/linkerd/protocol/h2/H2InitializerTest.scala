package io.buoyant.linkerd.protocol.h2

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.buoyant.h2._
import com.twitter.finagle.{Service, ServiceFactory, Stack}
import com.twitter.util.{Future, Promise, Time}
import io.buoyant.linkerd.protocol.H2Initializer
import io.buoyant.test.FunSuite
import io.buoyant.test.h2.StreamTestUtils._
import scala.language.reflectiveCalls

class H2InitializerTest extends FunSuite {

  test("path stack: services are not closed until streams are complete") {
    // Build a path stack that is controllable with promises. This is
    // only really useful for a single request.
    val serviceP, responseP, bodyP, respondingP, closedP = Promise[Unit]
    val http = new H2Initializer {
      val svc = new Service[Request, Response] {
        def apply(req: Request) = {
          val q = new AsyncQueue[Frame]
          val rsp = Response(Status.Ok, Stream(q))
          bodyP.onSuccess { _ => q.offer(Frame.Data.eos("woof")); () }
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
        (defaultRouter.pathStack ++ Stack.Leaf(Stack.Role("leaf"), sf)).make(params)
    }

    // The factory is returned immediately because it is wrapped in a
    // FactoryToService.
    val factory = http.make()
    val svcf = factory()
    assert(svcf.isDefined)
    val svc = await(svcf)

    // When a request is processed, first the service must be acquired
    // from the service factory, and then the response must be
    // returned from the service.
    val rspf = svc(Request(Headers.empty, Stream.empty()))
    assert(!rspf.isDefined)
    assert(!respondingP.isDefined)

    serviceP.setDone()
    eventually { assert(respondingP.isDefined) }
    assert(!rspf.isDefined)

    responseP.setDone()

    // When the response body is written, it must be fully read from
    // response before the service will be closed.
    bodyP.setDone()

    // ClassifiedRetryFilter will buffer the response and it will not be available until the buffer
    // is full or the last response frame has been read.
    eventually { assert(rspf.isDefined) }

    // Once the response is returned, FactoryToService tries to close
    // the service factory. Ensure that the service is not closed
    // until the response body is completely sent.
    val rsp = await(rspf)
    assert(!closedP.isDefined)

    // When the response body is written, it must be fully read from
    // response before the service will be closed.
    bodyP.setDone()
    assert(!closedP.isDefined)
    await(rsp.stream.readToEnd)

    eventually { assert(closedP.isDefined) }
  }
}
