package io.buoyant.router

import com.twitter.finagle._
import com.twitter.finagle.buoyant._
import com.twitter.finagle.tracing.Annotation.BinaryAnnotation
import com.twitter.finagle.tracing._
import com.twitter.util.{Future, Time}
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class RoutingFactoryTest extends FunSuite with Awaits {
  case class Request()
  case class Response()

  object Thrown extends Throwable
  val failingFilter = new SimpleFilter[Request, Response] {
    def apply(request: Request, service: Service[Request, Response]): Future[Response] =
      Future.exception(Thrown)
  }

  class TestTracer extends Tracer {
    var annotations: Seq[Annotation] = Nil
    def record(record: Record) = annotations = annotations :+ record.annotation
    def sampleTrace(traceId: TraceId) = None
  }

  def getFailures(annotations: Seq[Annotation]): Seq[String] =
    annotations.collect {
      case a: BinaryAnnotation if a.key == RoutingFactory.Annotations.Failure.key =>
        a.value.toString
    }

  val ok = Future.value(Service.mk[Request, Response] { _ => Future.value(Response()) })
  def mkClientFactory(client: Future[Service[Request, Response]] = ok) =
    new DstBindingFactory[Request, Response] {
      def status = Status.Open
      def close(d: Time) = Future.Unit
      def apply(dst: Dst, conn: ClientConnection) = client
    }

  def mkService(
    pathMk: RoutingFactory.Identifier[Request] = (_: Request) => Future.value(Dst.Path.empty),
    cache: DstBindingFactory[Request, Response] = mkClientFactory(),
    label: String = ""
  ) = new RoutingFactory(pathMk, cache, label).toService

  test("produces service that successfully serves requests") {
    val service = mkService()
    val res = await(service(Request()).liftToTry)
    assert(res.isReturn)
  }

  test("wraps Identifier failures") {
    val tracer = new TestTracer()
    Trace.letTracer(tracer) {
      val service = mkService(pathMk = (_: Request) => Future.exception(Thrown))
      intercept[RoutingFactory.UnknownDst[Request]] {
        await(service(Request()))
      }
    }
  }

  test("annotates router label") {
    val tracer = new TestTracer()
    Trace.letTracer(tracer) {
      val service = mkService(label = "customlabel")
      await(service(Request()))
      val annotations = tracer.annotations.collect {
        case a: BinaryAnnotation => a
      }
      assert(annotations.exists { a =>
        a.key == "router.label" && a.value == "customlabel"
      })
    }
  }

  // TODO remove when tests are replicated against the stack (where these features now belong)

  test("produces service that fails request and records on identification failure") {
    val tracer = new TestTracer()
    Trace.letTracer(tracer) {
      val service = mkService(pathMk = (_: Request) => Future.exception(Thrown))
      val res = await(service(Request()).liftToTry)
      assert(res.isThrow)
      assert(getFailures(tracer.annotations) ==
        Seq(RoutingFactory.Annotations.Failure.Identification.name))
    }
  }

  test("produces service that fails request and records on client acquisition failure") {
    val tracer = new TestTracer()
    Trace.letTracer(tracer) {
      val service = mkService(cache = mkClientFactory(Future.exception(Thrown)))
      val res = await(service(Request()).liftToTry)
      assert(res.isThrow)
      assert(getFailures(tracer.annotations) ==
        Seq(RoutingFactory.Annotations.Failure.ClientAcquisition.name))
    }
  }

  // test("produces service that fails request and records on destination failure") {
  //   val tracer = new TestTracer()
  //   Trace.letTracer(tracer) {
  //     val service = mkService(destinationFilter = _ => failingFilter)
  //     val res = await(service(Request()).liftToTry)
  //     assert(res.isThrow)
  //     assert(includesFailure(tracer.annotations, TransitTracer.ServiceFailure))
  //   }
  // }

}
