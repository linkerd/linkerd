package io.buoyant.consul.v1

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.finagle.param.HighResTimer
import com.twitter.finagle.service.{RetryBudget, RetryFilter, RetryPolicy}
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.tracing.Trace
import com.twitter.finagle.{ConnectionFailedException, Failure, Filter, http}
import com.twitter.io.Buf
import com.twitter.util.{NonFatal => _, _}
import io.buoyant.consul.log
import scala.util.control.NonFatal

trait BaseApi extends Closable {
  def client: Client

  def uriPrefix: String

  def backoffs: Stream[Duration]

  def stats: StatsReceiver

  def close(deadline: Time) = client.close(deadline)

  private[this] val infiniteRetryFilter = new RetryFilter[http.Request, http.Response](
    RetryPolicy.backoff(backoffs) {
      // We will assume 5xx are retryable, everything else is not for now
      case (req, Return(rep)) if rep.status.code >= 500 && rep.status.code < 600 =>
        log.error("Retrying Consul request '%s %s' on %s", req.method, req.uri, UnexpectedResponse(rep))
        true

      case (req, Throw(Failure(Some(err: ConnectionFailedException)))) if req.getParamNames().contains("index") =>
        log.error("Will not retry blocking index request '%s %s' on error: %s", req.method, req.uri, err)
        false
      // Don't retry on interruption
      case (_, Throw(e: Failure)) if e.isFlagged(Failure.Interrupted) => false
      case (req, Throw(NonFatal(ex))) =>
        log.error("Retrying Consul request '%s %s' on NonFatal error: %s", req.method, req.uri, ex)
        true
    },
    HighResTimer.Default,
    stats,
    RetryBudget.Infinite
  )

  def getClient(retry: Boolean) = {
    val retryFilter = if (retry)
      infiniteRetryFilter
    else
      Filter.identity[http.Request, http.Response]
    apiErrorFilter andThen retryFilter andThen client
  }

  private[v1] def mkreq(
    method: http.Method,
    path: String,
    consistency: Option[ConsistencyMode],
    optParams: (String, Option[String])*
  ): http.Request = {
    val consistencyMode = consistency.flatMap {
      case ConsistencyMode.Consistent =>
        Some("consistent" -> Some(true.toString))
      case ConsistencyMode.Stale =>
        Some("stale" -> Some(true.toString))
      case ConsistencyMode.Default =>
        None
    }
    val params = (consistencyMode ++ optParams).collect { case (k, Some(v)) => (k, v) }.toSeq
    val req = http.Request(path, params: _*)
    req.method = method
    req
  }

  private[this] val mapper = new ObjectMapper with ScalaObjectMapper
  mapper.registerModule(DefaultScalaModule)
  mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

  def parseJson[T: Manifest](buf: Buf): Try[T] = {
    val Buf.ByteArray.Owned(bytes, begin, end) = Buf.ByteArray.coerce(buf)
    Try(mapper.readValue[T](bytes, begin, end - begin))
  }

  private[v1] def executeJson[T: Manifest](
    req: http.Request,
    retry: Boolean
  ): Future[Indexed[T]] = {
    for {
      rsp <- Trace.letClear(getClient(retry)(req))
      value <- Future.const(parseJson[T](rsp.content))
    } yield Indexed[T](value, rsp.headerMap.get(Headers.Index))
  }

  private[v1] def executeRaw(req: http.Request, retry: Boolean): Future[Indexed[String]] = {
    Trace.letClear(getClient(retry)(req)).map { rsp =>
      Indexed[String](rsp.contentString, rsp.headerMap.get(Headers.Index))
    }
  }
}

object Headers {
  val Index = "X-Consul-Index"
}

case class Indexed[T](value: T, index: Option[String])
