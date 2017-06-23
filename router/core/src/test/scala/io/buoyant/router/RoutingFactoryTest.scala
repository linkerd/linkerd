package io.buoyant.router

import com.twitter.finagle._
import com.twitter.finagle.buoyant._
import com.twitter.finagle.naming.buoyant.DstBindingFactory
import com.twitter.finagle.tracing.Annotation.BinaryAnnotation
import com.twitter.finagle.tracing._
import com.twitter.util.{Future, Time}
import io.buoyant.router.RoutingFactory.IdentifiedRequest
import io.buoyant.test.FunSuite

class RoutingFactoryTest extends FunSuite {
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
      def apply(dst: Dst.Path, conn: ClientConnection) = client
    }

  def mkService(
    pathMk: RoutingFactory.Identifier[Request] = (req: Request) =>
      Future.value(new IdentifiedRequest[Request](Dst.Path.empty, req)),
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

      val req = Request()
      try {
        await(service(req))
        assert(false)
      } catch {
        case e: Exception => assert(e.getMessage.contains(req.toString))
        case _: Throwable => assert(false)
      }
    }
  }

  test("annotates router label") {
    val tracer = new TestTracer()
    Trace.letTracer(tracer) {
      val service = mkService(label = "customlabel")
      await(service(Request()))
      assert(tracer.annotations.exists {
        case BinaryAnnotation(key, value) => key == "router.label" && value == "customlabel"
        case _ => false
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

  test("Identifiers compose lazily") {
    var checked: Seq[String] = Nil
    def matching(s: String): RoutingFactory.Identifier[String] = {
      case `s` =>
        synchronized {
          checked = checked :+ s
        }
        val dst = Dst.Path(Path.Utf8(s), Dtab.empty, Dtab.empty)
        Future.value(new RoutingFactory.IdentifiedRequest(dst, s))
      case _ =>
        synchronized {
          checked = checked :+ s
        }
        Future.value(new RoutingFactory.UnidentifiedRequest("nah"))
    }
    val composed = RoutingFactory.Identifier.compose[String](
      matching("alpha"),
      matching("bravo"),
      matching("charlie")
    )

    assert(await(composed("alpha")).isInstanceOf[RoutingFactory.IdentifiedRequest[String]])
    assert(checked == Seq("alpha"))
    checked = Nil

    assert(await(composed("bravo")).isInstanceOf[RoutingFactory.IdentifiedRequest[String]])
    assert(checked == Seq("alpha", "bravo"))
    checked = Nil

    assert(await(composed("charlie")).isInstanceOf[RoutingFactory.IdentifiedRequest[String]])
    assert(checked == Seq("alpha", "bravo", "charlie"))
    checked = Nil

    assert(await(composed("donnie")).isInstanceOf[RoutingFactory.UnidentifiedRequest[String]])
    assert(checked == Seq("alpha", "bravo", "charlie"))
    checked = Nil
  }
}
