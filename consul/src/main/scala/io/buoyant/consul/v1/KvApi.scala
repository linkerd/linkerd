package io.buoyant.consul.v1

import com.twitter.conversions.time._
import com.twitter.finagle.http
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.stats.{DefaultStatsReceiver, StatsReceiver}
import com.twitter.util._

object KvApi {
  def apply(c: Client): KvApi = new KvApi(c, s"/$versionString")
}

class KvApi(
  override val client: Client,
  override val uriPrefix: String,
  override val backoffs: Stream[Duration] = Backoff.exponentialJittered(1.milliseconds, 5.seconds),
  override val stats: StatsReceiver = DefaultStatsReceiver
) extends BaseApi with Closable {
  val kvPrefix = s"$uriPrefix/kv"

  // https://www.consul.io/docs/agent/http/kv.html#single
  def list(
    path: String,
    datacenter: Option[String] = None,
    blockingIndex: Option[String] = None,
    separator: Option[String] = Some("/"),
    retry: Boolean = false
  ): Future[Indexed[Seq[String]]] = {
    val req = mkreq(
      http.Method.Get,
      s"$kvPrefix$path",
      "keys" -> Some(true.toString),
      "separator" -> separator,
      "index" -> blockingIndex,
      "dc" -> datacenter
    )
    executeJson[Seq[String]](req, retry)
  }

  /**
   * Get the key value
   *
   * https://www.consul.io/docs/agent/http/kv.html#single
   *
   * @param path path to the key, must start with /
   */
  def get(
    path: String,
    datacenter: Option[String] = None,
    blockingIndex: Option[String] = None,
    retry: Boolean = false
  ): Future[Indexed[String]] = {
    val req = mkreq(
      http.Method.Get,
      s"$kvPrefix$path",
      "raw" -> Some(true.toString),
      "index" -> blockingIndex,
      "dc" -> datacenter
    )
    executeRaw(req, retry)
  }

  /**
   * Store the key value
   *
   * https://www.consul.io/docs/agent/http/kv.html#single
   *
   * @param path path to the key, must start with /
   */
  def put(
    path: String,
    value: String,
    datacenter: Option[String] = None,
    cas: Option[String] = None,
    retry: Boolean = false
  ): Future[Boolean] = {
    val req = mkreq(
      http.Method.Put,
      s"$kvPrefix$path",
      "cas" -> cas,
      "dc" -> datacenter
    )
    req.setContentString(value)
    executeJson[Boolean](req, retry).map(_.value)
  }

  /**
   * Delete the key
   *
   * https://www.consul.io/docs/agent/http/kv.html#single
   *
   * @param path path to the key, must start with /
   */
  def delete(
    path: String,
    datacenter: Option[String] = None,
    cas: Option[String] = None,
    retry: Boolean = false
  ): Future[Boolean] = {
    val req = mkreq(
      http.Method.Delete,
      s"$kvPrefix$path",
      "cas" -> cas,
      "dc" -> datacenter
    )
    executeJson[Boolean](req, retry).map(_.value)
  }

}
