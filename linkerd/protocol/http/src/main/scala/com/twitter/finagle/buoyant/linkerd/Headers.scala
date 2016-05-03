package com.twitter.finagle.buoyant.linkerd

import com.twitter.finagle.{Name, Path, Service, ServiceFactory, SimpleFilter, Stack}
import com.twitter.finagle.buoyant.{Dst => BuoyantDst}
import com.twitter.finagle.http._
import com.twitter.finagle.tracing._
import com.twitter.util.Try
import java.util.Base64

// all of this based on com.twitter.finagle.http.Codec

object Headers {
  val Prefix = "l5d-"

  // TODO fold dtabs into this.
  object Ctx {
    val Key = Prefix + "ctx"

    /**
     * Get a trace id from a base64 encoded buffer.
     *
     * Based on com.twitter.finagle.tracing.Trace.idCtx.tryUnmarshal
     *
     * The wire format is (big-endian):
     *   ''reqId:8 parentId:8 traceId:8 flags:8''
     */
    def get(b64: String): Try[TraceId] =
      Try(Base64.getDecoder.decode(b64)).flatMap(TraceId.deserialize)

    def get(headers: HeaderMap): Option[TraceId] =
      for {
        header <- headers.get(Key)
        traceId <- get(header).toOption
      } yield traceId

    def set(headers: HeaderMap, id: TraceId): Unit = {
      val bytes = TraceId.serialize(id)
      val b64 = Base64.getEncoder().encodeToString(bytes)
      val _ = headers.set(Key, b64)
    }

    def clear(headers: HeaderMap): Unit = {
      val _ = headers.remove(Key)
    }
  }

  object RequestId {
    val Key = Prefix + "reqid"

    def set(headers: HeaderMap, traceId: SpanId): Unit = {
      val _ = headers.set(Key, traceId.toString)
    }
  }

  object Sample {
    val Key = Prefix + "sample"

    def get(headers: HeaderMap): Option[Float] =
      headers.get(Key).flatMap(s => Try(s.toFloat).toOption).filter(f => f >= 0f && f <= 1f)

    def clear(headers: HeaderMap): Unit = {
      val _ = headers.remove(Key)
    }
  }

  object Dst {
    val Path = Prefix + "dst-path"
    val Residual = Prefix + "dst-residual"
    val Bound = Prefix + "dst-bound"

    class PathFilter(path: Path) extends SimpleFilter[Request, Response] {
      private[this] val pathShow = path.show
      def apply(req: Request, service: Service[Request, Response]) = {
        req.headerMap(Path) = pathShow
        service(req).map { rsp =>
          rsp.headerMap(Path) = pathShow
          rsp
        }
      }
    }

    object PathFilter extends Stack.Module1[BuoyantDst.Path, ServiceFactory[Request, Response]] {
      val role = Stack.Role("Headers.Path")
      val description = s"Adds the '$Path' header to requests and responses"
      def make(dst: BuoyantDst.Path, factory: ServiceFactory[Request, Response]) =
        new PathFilter(dst.path).andThen(factory)
    }

    class BoundFilter(bound: Name.Bound) extends SimpleFilter[Request, Response] {
      private[this] val boundShow = bound.idStr
      private[this] val pathShow = bound.path match {
        case com.twitter.finagle.Path.empty => None
        case path => Some(path.show)
      }
      private[this] def annotate(msg: Message): Unit = {
        msg.headerMap(Bound) = boundShow
        for (p <- pathShow) {
          msg.headerMap(Residual) = p
        }
      }

      def apply(req: Request, service: Service[Request, Response]) = {
        annotate(req)
        service(req).map { rsp =>
          annotate(rsp)
          rsp
        }
      }
    }

    object BoundFilter extends Stack.Module1[BuoyantDst.Bound, ServiceFactory[Request, Response]] {
      val role = Stack.Role("Headers.Bound")
      val description = s"Adds the $Bound and $Residual headers to requests and responses"
      def make(dst: BuoyantDst.Bound, factory: ServiceFactory[Request, Response]) =
        new BoundFilter(dst.name).andThen(factory)
    }

  }

  val Err = Prefix + "err"
}
