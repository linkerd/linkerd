package io.buoyant.linkerd.protocol

import com.twitter.conversions.time._
import com.twitter.finagle.{Service, ServiceFactory, Stack, param}
import com.twitter.finagle.http.{Request, Response, Status, Version}
import com.twitter.finagle.service.{Retries, RetryBudget}
import com.twitter.finagle.stack.nilStack
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.io.Reader
import com.twitter.util.{Future, MockTimer, Promise, Time}
import io.buoyant.linkerd.protocol.http.ResponseClassifiers
import io.buoyant.test.Awaits
import org.scalatest.FunSuite
import org.scalatest.concurrent.Eventually
import scala.language.reflectiveCalls

class HttpInitializerTest extends FunSuite with Awaits with Eventually {

  test("path stack: services are not closed until streams are complete") {
    // Build a path stack that is controllable with promises. This is
    // only really useful for a single request.
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

  test("path stack: retries") {
    @volatile var requests = 0
    val http = new HttpInitializer {
      val sf = ServiceFactory.const(Service.mk[Request, Response] { req =>
        requests += 1
        Future.value(Response(req.version, Status.InternalServerError))
      })

      def make(params: Stack.Params = Stack.Params.empty) =
        (defaultRouter.pathStack ++ Stack.Leaf(Stack.Role("leaf"), sf)).make(params)
    }

    val budget = RetryBudget(10.seconds, 0, 0.5)
    val params = Stack.Params.empty +
      param.ResponseClassifier(ResponseClassifiers.RetryableReadFailures) +
      Retries.Budget(budget)
    val factory = http.make(params)

    val service = await(factory())

    // First request just returns, since retry budget hasn't yet accrued.
    val response0 = await(service(Request()))
    assert(requests == 1)

    // The second request is retryable because of the 50% retry
    // budget.
    val response1 = await(service(Request()))
    assert(requests == 3)
  }

  class WildErr extends Exception
  test("path stack: error handling") {
    @volatile var requests = 0
    val http = new HttpInitializer {
      val sf = ServiceFactory.const(Service.mk[Request, Response] { req =>
        requests += 1
        Future.exception(new WildErr)
      })

      def make(params: Stack.Params = Stack.Params.empty) =
        (defaultRouter.pathStack ++ Stack.Leaf(Stack.Role("leaf"), sf)).make(params)
    }

    val stats = new InMemoryStatsReceiver
    val factory = http.make(Stack.Params.empty + param.Stats(stats))
    val service = await(factory())

    val response = await(service(Request()))
    assert(requests == 1)
    assert(response.status == Status.BadGateway)
    assert(response.headerMap.contains("l5d-err"))

    val counter = Seq("failures", "io.buoyant.linkerd.protocol.HttpInitializerTest$WildErr")
    assert(stats.counters.get(counter) == Some(1))
  }
}
