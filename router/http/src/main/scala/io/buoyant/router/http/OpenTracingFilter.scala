package io.buoyant.router.http

import com.twitter.finagle._
import com.twitter.finagle.http.{HeaderMap, Request, Response}
import com.twitter.finagle.tracing.Trace
import com.twitter.util.Future
import io.opentracing.References
import io.opentracing.propagation.{Format, TextMap}
import io.opentracing.tag.Tags
import io.opentracing.util.GlobalTracer

object OpenTracingFilter {
  val role = Stack.Role("openTracing")
  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module1[param.Tracer, ServiceFactory[Request, Response]] {
      val role = OpenTracingFilter.role
      val description = "Traces HTTP-specific request metadata"

      def make(_tracer: param.Tracer, next: ServiceFactory[Request, Response]) = {
        val param.Tracer(tracer) = _tracer
        if (!GlobalTracer.isRegistered) next
        else (new OpenTracingFilter).andThen(next)
      }
    }
}

class OpenTracingFilter extends SimpleFilter[Request, Response] {
  override def apply(request: Request, service: Service[Request, Response]): Future[Response] = {
    recordRequest(request)
    service(request)
  }

  private[this] def recordRequest(req: Request): Unit = {
    if (GlobalTracer.isRegistered) {
      val tracer = GlobalTracer.get()
      val spanBuilder = tracer.buildSpan(req.method.name)
        .withTag(Tags.COMPONENT.getKey, "linkerd")
        .withTag(Tags.HTTP_METHOD.getKey, req.method.name)

      val parent = tracer.extract(
        Format.Builtin.HTTP_HEADERS,
        new HeaderMapExtractAdapter(req.headerMap)
      )
      if (parent != null) {
        spanBuilder.addReference(References.FOLLOWS_FROM, parent)
      }

      val span = spanBuilder.startManual()

      tracer.inject(span.context(), Format.Builtin.HTTP_HEADERS,
        new HeaderMapInjectAdapter(req.headerMap))

      Trace.recordBinary("opentracing-span", span)
    }
  }
}

class HeaderMapExtractAdapter(headerMap: HeaderMap) extends TextMap {

  override def put(key: String, value: String) = {
    throw new UnsupportedOperationException("HeaderMapExtractAdapter should only be used with Tracer.extract()")
  }

  override def iterator() = {
    import scala.collection.JavaConverters._
    headerMap.asJava.entrySet().iterator()
  }
}

class HeaderMapInjectAdapter(headerMap: HeaderMap) extends TextMap {

  override def put(key: String, value: String) = {
    headerMap.set(key, value)
    ()
  }

  override def iterator() = throw new UnsupportedOperationException("iterator should never be used with Tracer.inject()")
}
