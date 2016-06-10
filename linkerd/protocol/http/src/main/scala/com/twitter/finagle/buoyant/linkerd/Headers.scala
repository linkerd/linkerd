package com.twitter.finagle.buoyant.linkerd

import com.twitter.finagle.{Deadline => FDeadline, Dtab => FDtab, Status => _, _}
import com.twitter.finagle.buoyant.{Dst => BuoyantDst}
import com.twitter.finagle.context.Contexts
import com.twitter.finagle.http._
import com.twitter.finagle.tracing._
import com.twitter.util.{Future, Return, Throw, Time, Try}
import java.util.Base64

// all of this based on com.twitter.finagle.http.Codec

object Headers {
  val Prefix = "l5d-"

  object Ctx {

    /**
     * A serverside stack module that extracts contextual information
     * from requests and configures the local
     * `com.twitter.finagle.Context` appropriately. Currently this includes:
     *   - Deadline
     *   - Dtab
     *
     * Note that currently, the dtabs read by this module are
     * appeneded to that specified by the `l5d-dtab` header.  The
     * `dtab-local` header should be considered deprecated in favor of
     * `l5d-dtab, and will not be supported in the future.
     *
     * Note that trace configuration is handled by [[HttpTraceInitializer.server]].
     */
    val serverModule: Stackable[ServiceFactory[Request, Response]] =
      new Stack.Module0[ServiceFactory[Request, Response]] {
        val role = Stack.Role("DeadlineExtract")
        val description = "Extracts deadlines from http headers"

        val deadline = new Deadline.ServerFilter
        val dtab = new Dtab.ServerFilter

        def make(next: ServiceFactory[Request, Response]) =
          deadline.andThen(dtab).andThen(next)
      }

    /**
     * A clientside stack module that injects local contextual
     * information onto downstream requests.  Currently this includes:
     *   - Deadline
     *
     * Note that Dtabs are *not* encoded by this filter, since the
     * HttpClientDispatcher is currently responsible for encoding the
     * `dtab-local` header.  In a future release, Dtabs will be
     * encoded into the `l5d-ctx-dtab` header.
     *
     * Note that trace configuration is handled by [[HttpTraceInitializer.client]].
     */
    val clientModule: Stackable[ServiceFactory[Request, Response]] =
      new Stack.Module0[ServiceFactory[Request, Response]] {
        val role = Stack.Role("DeadlineInject")
        val description = "Injects deadlines into http headers"

        // TODO use Dtab.ClientFilter once this module can replace finagle's dtab encoding logic.
        val deadline = new Deadline.ClientFilter

        def make(next: ServiceFactory[Request, Response]) =
          deadline.andThen(next)
      }

    val Prefix = Headers.Prefix + "ctx-"

    object Trace {
      val Key = Prefix + "trace"

      /**
       * Get a trace id from a base64 encoded buffer.
       *
       * Based on com.twitter.finagle.tracing.Trace.idCtx.tryUnmarshal
       *
       * The wire format is (big-endian):
       *   ''reqId:8 parentId:8 traceId:8 flags:8''
       */
      def read(b64: String): Try[TraceId] =
        Try { Base64.getDecoder.decode(b64) }.flatMap(TraceId.deserialize(_))

      def get(headers: HeaderMap): Option[TraceId] =
        for {
          header <- headers.get(Key)
          traceId <- read(header).toOption
        } yield traceId

      def set(headers: HeaderMap, id: TraceId): Unit = {
        val bytes = TraceId.serialize(id)
        val b64 = Base64.getEncoder.encodeToString(bytes)
        val _ = headers.set(Key, b64)
      }

      def clear(headers: HeaderMap): Unit = {
        val _ = headers.remove(Key)
      }
    }

    object Deadline {
      val Key = Prefix + "deadline"

      def read(v: String): Try[FDeadline] = Try {
        val values = v.split(' ')
        val timestamp = Time.fromNanoseconds(values(0).toLong)
        val deadline = Time.fromNanoseconds(values(1).toLong)
        FDeadline(timestamp, deadline)
      }

      def get(headers: HeaderMap): Option[FDeadline] =
        headers.getAll(Key).foldLeft[Option[FDeadline]](None) { (d0, v) =>
          (d0, read(v).toOption) match {
            case (Some(d0), Some(d1)) => Some(FDeadline.combined(d0, d1))
            case (d0, d1) => d0.orElse(d1)
          }
        }

      def write(d: FDeadline): String =
        s"${d.timestamp.inNanoseconds} ${d.deadline.inNanoseconds}""

      def set(headers: HeaderMap, deadline: FDeadline): Unit = {
        val _ = headers.set(Key, write(deadline))
      }

      def clear(headers: HeaderMap): Unit = {
        val _ = headers.remove(Key)
      }

      /**
       * Extract the deadline from the request and, if it exists, use
       * either that deadline or the one already on the local context,
       * whichever is stricter.
       */
      class ServerFilter extends SimpleFilter[Request, Response] {
        def apply(req: Request, service: Service[Request, Response]) =
          get(req.headerMap) match {
            case None => service(req)
            case Some(reqDeadline) =>
              clear(req.headerMap)
              val deadline = FDeadline.current match {
                case None => reqDeadline
                case Some(current) => FDeadline.combined(reqDeadline, current)
              }
              Contexts.broadcast.let(FDeadline, deadline) {
                service(req)
              }
          }
      }

      class ClientFilter extends SimpleFilter[Request, Response] {
        def apply(req: Request, service: Service[Request, Response]) =
          FDeadline.current match {
            case None => service(req)
            case Some(deadline) =>
              set(req.headerMap, deadline)
              service(req)
          }
      }
    }

    object Dtab {
      val UserKey = Headers.Prefix + "dtab"
      val CtxKey = Ctx.Prefix + "dtab"
      private val EmptyReturn = Return(FDtab.empty)

      def get(headers: HeaderMap, key: String): Try[FDtab] =
        if (!headers.contains(key)) EmptyReturn
        else Try { FDtab(headers.getAll(key).view.flatMap(FDtab.read(_)).toIndexedSeq) }

      def get(headers: HeaderMap): Try[FDtab] =
        for {
          ctx <- get(headers, CtxKey)
          user <- get(headers, UserKey)
        } yield ctx ++ user

      def clear(headers: HeaderMap): Unit = {
        val _c = headers.remove(CtxKey)
        val _u = headers.remove(UserKey)
      }

      def set(dtab: FDtab, msg: Message): Unit =
        if (dtab.nonEmpty) {
          val _ = msg.headerMap.set(CtxKey, dtab.show)
        }

      /**
       * Extract a Dtab from the L5d-Ctx-Dtab and L5d-Dtab headers (in
       * that order) and append them to the local context.
       *
       * The L5d-Ctx-Dtab header is intended to be set by a linkerd
       * instance, while the L5d-Dtab header is intended to be set by
       * a user who wants to override delegation.
       *
       * @todo use DtabFilter.Injector once it is released.
       */
      class ServerFilter extends SimpleFilter[Request, Response] {

        def apply(req: Request, service: Service[Request, Response]) =
          get(req.headerMap) match {
            case Throw(e) =>
              invalidResponse(e.getMessage)
            case Return(dtab) =>
              clear(req.headerMap)
              FDtab.local ++= dtab
              service(req)
          }

        private[this] def invalidResponse(msg: String): Future[Response] = {
          val rspTxt = s"Invalid Dtab headers: $msg"
          val rsp = Response(Status.BadRequest)
          rsp.contentType = "text/plain; charset=UTF-8"
          rsp.contentLength = rspTxt.getBytes.length
          rsp.contentString = rspTxt
          Future.value(rsp)
        }
      }

      /**
       * Encodes the local dtab into the L5d-Ctx-Dtab header.
       *
       * @todo use DtabFilter.Extractor once it is released.
       */
      class ClientFilter extends SimpleFilter[Request, Response] {
        def apply(req: Request, service: Service[Request, Response]) = {
          set(FDtab.local, req)
          service(req)
        }
      }
    }
  }

  object RequestId {
    val Key = Prefix + "reqid"

    def set(headers: HeaderMap, traceId: SpanId): Unit = {
      val _ = headers.set(Key, traceId.toString)
    }
  }

  /**
   * Sets the sample rate to determine whether this span should be traced.
   */
  object Sample {
    val Key = Prefix + "sample"

    def get(headers: HeaderMap): Option[Float] =
      headers.get(Key).flatMap { s =>
        Try(s.toFloat).toOption.map {
          case v if v < 0 => 0.0f
          case v if v > 1 => 1.0f
          case v => v
        }
      }

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
        req.headers().set(Path, pathShow)
        service(req)
      }
    }

    object PathFilter extends Stack.Module1[BuoyantDst.Path, ServiceFactory[Request, Response]] {
      val role = Stack.Role("Headers.Path")
      val description = s"Adds the '$Path' header to requests and responses"
      def make(dst: BuoyantDst.Path, factory: ServiceFactory[Request, Response]) =
        new PathFilter(dst.path).andThen(factory)
    }

    /**
     * Encodes bound and residual paths onto downstream requests
     */
    class BoundFilter(bound: Name.Bound) extends SimpleFilter[Request, Response] {
      private[this] val boundShow = bound.idStr
      private[this] val pathShow = bound.path match {
        case com.twitter.finagle.Path.empty => None
        case path => Some(path.show)
      }
      private[this] def annotate(msg: Message): Unit = {
        val headers = msg.headers()
        val _b = headers.set(Bound, boundShow)
        pathShow match {
          case None =>
          case Some(p) =>
            val _p = headers.set(Residual, p)
        }
      }

      def apply(req: Request, service: Service[Request, Response]) = {
        annotate(req)
        service(req)
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
