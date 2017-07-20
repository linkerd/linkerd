package io.buoyant.router

import com.twitter.finagle.{Service, ServiceFactory, Stack, param, Path}
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.context.Contexts
import com.twitter.finagle.stack._
import com.twitter.finagle.service.{ReqRep, ResponseClass, ResponseClassifier}
import com.twitter.finagle.tracing._
import com.twitter.util.{Future, Return, Throw}
import io.buoyant.router.context.{DstPathCtx, ResponseClassifierCtx}
import io.buoyant.test.FunSuite

class ClassifiedTracingTest extends FunSuite {

  trait Ctx {
    val classifier = ResponseClassifier.named("test") {
      case ReqRep(_, Throw(_)) => ResponseClass.NonRetryableFailure
      case ReqRep("retry", Return(_)) => ResponseClass.RetryableFailure
      case ReqRep(_, Return(s: Double)) => ResponseClass.Successful(s)
    }

    var tracer = new BufferingTracer
    var sampled: Option[Boolean] = None

    lazy val factory = {
      val sf = ServiceFactory.const(Service.mk[String, Double] {
        case "fail" => Future.exception(new Exception)
        case s => Future.value(0.84)
      })
      val stk = DstPathCtx.Setter.module[String, Double] +:
        ResponseClassifierCtx.Setter.module[String, Double] +:
        ClassifiedTracing.module[String, Double] +:
        Stack.Leaf(Endpoint, sf)
      val params = Stack.Params.empty +
        param.Tracer(tracer) +
        param.ResponseClassifier(classifier) +
        Dst.Path(Path.read("/foo"))
      stk.make(params)
    }

    lazy val svc = Service.mk[String, Double] { s =>
      Trace.letTracerAndId(tracer, TraceId(None, None, SpanId(3), sampled), false) {
        factory().flatMap { svc =>
          svc(s).ensure {
            val _ = svc.close()
          }
        }
      }
    }

    def call(s: String): Unit = {
      val _ = await(svc(s).liftToTry)
    }
  }

  test("module installs a classified tracing filter") {
    val ctx = new Ctx {}
    import ctx._

    tracer.clear()
    call("ok")
    assert(tracer.iterator.map(_.annotation).toSeq ==
      Seq(Annotation.BinaryAnnotation("l5d.success", 0.84)))

    tracer.clear()
    call("fail")
    assert(tracer.iterator.map(_.annotation).toSeq ==
      Seq(Annotation.Message("l5d.failure")))

    tracer.clear()
    call("retry")
    assert(tracer.iterator.map(_.annotation).toSeq ==
      Seq(Annotation.Message("l5d.retryable")))
  }

  test("module doesn't install tracing filter when no tracer is configured") {
    val ctx = new Ctx {}
    import ctx._

    tracer = new BufferingTracer {
      override def isNull = true
    }

    call("ok")
    assert(tracer.iterator.map(_.annotation).toSeq == Seq())

    call("fail")
    assert(tracer.iterator.map(_.annotation).toSeq == Seq())

    call("retry")
    assert(tracer.iterator.map(_.annotation).toSeq == Seq())
  }

  test("traces when no sampling configured") {
    val ctx = new Ctx {}
    import ctx._

    sampled = None
    call("ok")
    assert(tracer.iterator.map(_.annotation).toSeq ==
      Seq(Annotation.BinaryAnnotation("l5d.success", 0.84)))
  }

  test("traces when sampling is true") {
    val ctx = new Ctx {}
    import ctx._

    sampled = Some(true)
    call("ok")
    assert(tracer.iterator.map(_.annotation).toSeq ==
      Seq(Annotation.BinaryAnnotation("l5d.success", 0.84)))
  }
}
