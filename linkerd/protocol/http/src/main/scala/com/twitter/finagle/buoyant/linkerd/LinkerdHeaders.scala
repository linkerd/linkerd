package com.twitter.finagle.buoyant.linkerd

import com.twitter.finagle.{Dtab => FDtab, Status => _, _}
import com.twitter.finagle.buoyant.{Dst => BuoyantDst}
import com.twitter.finagle.context.{Contexts, Deadline => FDeadline}
import com.twitter.finagle.http.{param => hparam, _}
import com.twitter.finagle.tracing._
import com.twitter.util.{Future, Return, Throw, Time, Try}
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.util.Base64
import scala.collection.breakOut

/**
 * The finagle http stack manages a set of context headers that are
 * read from server requests and written to client requests. The
 * [[Headers]] module replaces these headers with linkerd-specific
 * headers (prefixed by l5d-).
 *
 * Context headers, read and written by each linkerd instance, include:
 *
 *   - `l5d-ctx-deadline`
 *   - `l5d-ctx-dtab`
 *   - `l5d-ctx-trace`
 *
 * Additionally, linkerd honors the following headers on incoming requests:
 *
 *   - `l5d-dtab`: a client-specified delegation override
 *   - `l5d-sample`: a client-specified trace sample rate override
 *
 * In addition to the context headers, linkerd may emit the following
 * headers on outgoing requests:
 *
 *   - `l5d-dst-logical`: the logical name of the request as identified by linkerd
 *   - `l5d-dst-concrete`: the concrete client name after delegation
 *   - `l5d-dst-residual`: an optional residual path remaining after delegation
 *   - `l5d-reqid`: a token that may be used to correlate requests in
 * a callgraph across services and linkerd instances
 *
 * And in addition to the context headers, linkerd may emit the following
 * headers on outgoing responses:
 *
 *   - `l5d-err`: indicates a linkerd-generated error. Error responses
 * that do not have this header are application errors.
 *   - `l5d-retryable`: if true, the request for this response is known to be
 * safe to retry (for example, because it was not delivered to
 * its destination)
 */
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
     * Note that the dtabs read by this module are appeneded to that specified
     * by the `l5d-dtab` header.
     *
     * Note that trace configuration is handled by
     * [[HttpTraceInitializer.serverModule]].
     */
    val serverModule: Stackable[ServiceFactory[Request, Response]] =
      new Stack.Module1[hparam.MaxHeaderSize, ServiceFactory[Request, Response]] {
        val role = Stack.Role("ServerContext")
        val description = "Extracts linkerd context from http headers"

        val deadline = new Deadline.ServerFilter

        def dtab(maxHeaderSize: Int) = new Dtab.ServerFilter(maxHeaderSize)

        def make(maxHeaderSize: hparam.MaxHeaderSize, next: ServiceFactory[Request, Response]) =
          deadline.andThen(dtab(maxHeaderSize.size.bytes.toInt)).andThen(next)
      }

    val clearServerModule: Stackable[ServiceFactory[Request, Response]] =
      new Stack.Module0[ServiceFactory[Request, Response]] {
        val role = serverModule.role
        val description = "Clears linkerd context from http request headers"

        val deadline = new Deadline.ClearServerFilter
        val dtab = new Dtab.ClearServerFilter
        val sample = new Sample.ClearServerFilter
        val trace = new Trace.ClearServerFilter
        val misc = new ClearMiscServerFilter

        def make(next: ServiceFactory[Request, Response]) =
          deadline.andThen(dtab).andThen(sample).andThen(trace).andThen(misc).andThen(next)
      }

    /**
     * A clientside stack module that injects local contextual
     * information onto downstream requests.  Currently this includes:
     *   - Deadline
     *   - Dtab
     *
     * Note that trace configuration is handled by
     * [[HttpTraceInitializer.clientModule]].
     */
    val clientModule: Stackable[ServiceFactory[Request, Response]] =
      new Stack.Module0[ServiceFactory[Request, Response]] {
        val role = Stack.Role("ClientContext")
        val description = "Injects linkerd context into http headers"

        // TODO use Dtab.ClientFilter once this module can replace
        //      finagle's dtab encoding logic.
        val deadline = new Deadline.ClientFilter
        val dtab = new Dtab.ClientFilter

        def make(next: ServiceFactory[Request, Response]) =
          deadline.andThen(dtab).andThen(next)
      }

    val Prefix = Headers.Prefix + "ctx-"

    /**
     * The `l5d-ctx-deadline` header propagates a request
     * deadline. Each router server may use this deadline to cancel or
     * reject work.
     *
     * Each router client sets a deadline that it is at least as
     * strict as the deadline it received. If an incoming request has
     * a deadline, the outgoing request MUST have a
     * deadline. Otherwise, outgoing requests MAY have a deadline.
     */
    object Deadline {
      val Key = Prefix + "deadline"

      def read(v: String): FDeadline = {
        val values = v.split(' ')
        val timestamp = Time.fromNanoseconds(values(0).toLong)
        val deadline = Time.fromNanoseconds(values(1).toLong)
        FDeadline(timestamp, deadline)
      }

      /**
       * Read all `l5d-ctx-deadline` headers and return the strictest
       * combination.
       */
      def get(headers: HeaderMap): Option[FDeadline] =
        headers.getAll(Key).foldLeft[Option[FDeadline]](None) { (d0, v) =>
          (d0, Try(read(v)).toOption) match {
            case (Some(d0), Some(d1)) => Some(FDeadline.combined(d0, d1))
            case (d0, d1) => d0.orElse(d1)
          }
        }

      def write(d: FDeadline): String =
        s"${d.timestamp.inNanoseconds} ${d.deadline.inNanoseconds}"

      def set(headers: HeaderMap, deadline: FDeadline): Unit = {
        val _ = headers.set(Key, write(deadline))
      }

      def clear(headers: HeaderMap): Unit = {
        val _ = headers.remove(Key)
      }

      /**
       * Extract the deadline from the request and, if it exists, use
       * either the strictest combination of deadlines.
       *
       * Clears deadline headers from the request. This means that the
       * client is responsible for encoding outgoing deadlines.
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

      class ClearServerFilter extends SimpleFilter[Request, Response] {
        def apply(req: Request, service: Service[Request, Response]) = {
          clear(req.headerMap)
          service(req)
        }
      }

      /**
       * If a deadline is set, encode it on downstream requests.
       *
       * Clears any existing deadline headers from the request.
       */
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

    /**
     * There are two headers used to control local Dtabs in linkerd:
     *
     *   1. `l5d-ctx-dtab` is read and _written_ by linkerd. It is
     * intended to managed entirely by linkerd, and applications
     * should only forward requests prefixed by `l5d-ctx-*`.
     *
     *   2. `l5d-dtab` is to be provided by users. Applications are
     * not required to forward `l5d-dtab` when fronted by
     * linkerd.
     *
     * `l5d-dtab` is appended to `l5d-ctx-dtab`, so that user-provided
     * delegations take precdence.
     */
    object Dtab {
      val CtxKey = Ctx.Prefix + "dtab"
      val UserKey = Headers.Prefix + "dtab"

      private val EmptyReturn = Return(FDtab.empty)

      def get(headers: HeaderMap, key: String): Try[FDtab] =
        if (!headers.contains(key)) EmptyReturn
        else Try {
          FDtab(headers.getAll(key).flatMap(FDtab.read(_))(breakOut))
        }

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
      class ServerFilter(maxHeaderSize: Int) extends SimpleFilter[Request, Response] {

        def apply(req: Request, service: Service[Request, Response]) =
          get(req.headerMap) match {
            case Throw(e) =>
              Future.value(Err.respond(e.getMessage, Status.BadRequest, maxHeaderSize))
            case Return(dtab) =>
              clear(req.headerMap)
              FDtab.local ++= dtab
              service(req)
          }
      }

      class ClearServerFilter extends SimpleFilter[Request, Response] {
        def apply(req: Request, service: Service[Request, Response]) = {
          clear(req.headerMap)
          service(req)
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

    object Trace {
      val Key = Prefix + "trace"
      val OpentracingSpanHeader = "x-b3-spanid";
      val OpentracingParentHeader = "x-b3-parentspanid";
      val OpentracingTraceHeader = "x-b3-traceid";
      val OpentracingSampleHeader = "x-b3-sampled";
      val OpentracingFlagsHeader = "x-b3-flags";

      /**
       * Get a trace id from a base64 encoded buffer.
       *
       * Based on com.twitter.finagle.tracing.Trace.idCtx.tryUnmarshal
       *
       * The wire format is (big-endian):
       * ''reqId:8 parentId:8 traceId:8 flags:8''
       */
      def read(b64: String): Try[TraceId] =
        Try {
          Base64.getDecoder.decode(b64)
        }.flatMap(TraceId.deserialize(_))

      def get(headers: HeaderMap): Option[TraceId] =
        if (headers.contains(OpentracingSpanHeader)) {
          Some(TraceId.apply(SpanId.fromString(headers.get(OpentracingTraceHeader).get), SpanId.fromString(headers.get(OpentracingParentHeader).get), SpanId.fromString(headers.get(OpentracingSpanHeader).get).get, Some(if (headers.get(OpentracingSampleHeader).get.toInt == 1) true else false), Flags.apply(headers.get(OpentracingFlagsHeader).get.toInt)));
        } else {
          for {
            header <- headers.get(Key)
            traceId <- read(header).toOption
          } yield traceId
        }

      def set(headers: HeaderMap, id: TraceId): Unit = {
        val bytes = TraceId.serialize(id)
        val b64 = Base64.getEncoder.encodeToString(bytes)
        val _ = headers.set(Key, b64)
        val __ = headers.set(OpentracingSpanHeader, id.spanId.toString)
        val ___ = headers.set(OpentracingTraceHeader, id.traceId.toString)
        val ____ = headers.set(OpentracingParentHeader, id.parentId.toString)
      }

      def clear(headers: HeaderMap): Unit = {
        val _ = headers.remove(Key)
      }

      class ClearServerFilter extends SimpleFilter[Request, Response] {
        def apply(req: Request, service: Service[Request, Response]) = {
          clear(req.headerMap)
          service(req)
        }
      }
    }
  }

  /**
   * The `l5d-reqid` header is used to provide applications with a
   * token that can be used in logging to correlate requests. We use
   * the _root_ span id so that this key can be used to correlate all
   * related requests (i.e. in log messages) across services and
   * linkerd instances.
   */
  object RequestId {
    val Key = Prefix + "reqid"

    def set(headers: HeaderMap, traceId: TraceId): Unit = {
      val _ = headers.set(Key, traceId.traceId.toString)
    }
  }

  /**
   * The `l5d-sample` lets clients determine the sample rate of a
   * given request. Tracers may, of course, choose to enforce
   * additional sampling, so setting this header cannot ensure that a
   * trace is recorded.
   *
   * `l5d-sample` values should be on [0.0, 1.0], however values
   * outside of this range are rounded to the nearest valid value so
   * that negative numbers are treated as 0 and positive numbers
   * greater than 1 are rounded to 1. At 1.0, the trace is marked as
   * sampled on all downstream requestes.
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

    class ClearServerFilter extends SimpleFilter[Request, Response] {
      def apply(req: Request, service: Service[Request, Response]) = {
        clear(req.headerMap)
        service(req)
      }
    }
  }

  /**
   * Dst headers are encoded on outgoing requests so that downstream
   * services are able to know how they are named by
   * linkerd. Specifically, the `l5d-dst-residual` header may be
   * useful to services that act as proxies and need to determine the
   * next hop.
   */
  object Dst {
    val Path = Prefix + "dst-service"
    val Bound = Prefix + "dst-client"
    val Residual = Prefix + "dst-residual"

    /** Encodes `l5d-dst-service` on outgoing requests. */
    class PathFilter(path: Path) extends SimpleFilter[Request, Response] {
      private[this] val pathShow = path.show

      def apply(req: Request, service: Service[Request, Response]) = {
        req.headerMap.set(Path, pathShow)
        service(req)
      }
    }

    object PathFilter {
      val module: Stackable[ServiceFactory[Request, Response]] =
        new Stack.Module1[BuoyantDst.Path, ServiceFactory[Request, Response]] {
          val role = Stack.Role("Headers.Path")
          val description = s"Adds the '$Path' header to requests and responses"

          def make(dst: BuoyantDst.Path, factory: ServiceFactory[Request, Response]) =
            new PathFilter(dst.path).andThen(factory)
        }
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
        val headers = msg.headerMap
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

    object BoundFilter {
      val module: Stackable[ServiceFactory[Request, Response]] =
        new Stack.Module1[BuoyantDst.Bound, ServiceFactory[Request, Response]] {
          val role = Stack.Role("Headers.Bound")
          val description = s"Adds the $Bound and $Residual headers to requests and responses"

          def make(dst: BuoyantDst.Bound, factory: ServiceFactory[Request, Response]) =
            new BoundFilter(dst.name).andThen(factory)
        }
    }
  }

  class ClearMiscServerFilter extends SimpleFilter[Request, Response] {
    def apply(req: Request, service: Service[Request, Response]) = {
      clearLinkerdHeaders(req)
      service(req).map { rsp =>
        rsp.headerMap.get(Err.Key)
          .foreach(_ => rsp.clearContent())
        clearLinkerdHeaders(rsp)
        rsp
      }
    }

    private def clearLinkerdHeaders(msg: Message) = {
      for (k <- msg.headerMap.keys) {
        if (k.toLowerCase.startsWith(Headers.Prefix)) {
          msg.headerMap -= k
        }
      }
    }
  }

  /**
   * The `l5d-err` header is set on all responses in which linkerd
   * encountered an error. It can be used to distinguish linkerd
   * responses from application responses.
   */
  object Err {
    val Key = Prefix + "err"

    def respond(msg: String, status: Status = Status.InternalServerError, maxHeaderSize: Int): Response = {
      val rsp = Response(status)
      val header = URLEncoder.encode(msg, ISO_8859_1.toString)
      rsp.headerMap(Key) = if (header.length > maxHeaderSize) {
        header.substring(0, maxHeaderSize)
      } else {
        header
      }
      rsp.contentType = MediaType.Txt
      rsp.contentString = msg
      rsp
    }
  }

  /**
   * The `l5d-retryable` header indicates that the request for this response is
   * known to be safe to retry (for example, because it was not delivered to its
   * destination)
   */
  object Retryable {
    val Key = Prefix + "retryable"

    def set(headers: HeaderMap, retryable: Boolean): Unit = {
      val _ = headers.set(Key, retryable.toString)
    }

    def get(headers: HeaderMap): Boolean =
      headers.get(Key).flatMap { value =>
        Try(value.toBoolean).toOption
      }.getOrElse(false)
  }

}
