package io.buoyant.consul.v1

import java.util.Base64

import com.twitter.conversions.time._
import com.twitter.finagle.http
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.stats.{DefaultStatsReceiver, StatsReceiver}
import com.twitter.util._

trait KvApi {
  def list(
    path: String,
    datacenter: Option[String] = None,
    blockingIndex: Option[String] = None,
    separator: Option[String] = None,
    retry: Boolean = false
  ): Future[Indexed[Seq[String]]]

  def get(
    path: String,
    datacenter: Option[String] = None,
    blockingIndex: Option[String] = None,
    recurse: Boolean = false,
    retry: Boolean = false
  ): Future[Indexed[Seq[Key]]]

  def put(
    path: String,
    value: String,
    datacenter: Option[String] = None,
    cas: Option[String] = None,
    retry: Boolean = false
  ): Future[Boolean]

  def delete(
    path: String,
    datacenter: Option[String] = None,
    cas: Option[String] = None,
    recurse: Boolean = false,
    retry: Boolean = false
  ): Future[Boolean]

}

object KvApiV1 {
  def apply(c: Client): KvApiV1 = new KvApiV1(c, s"/$versionString")
}

class KvApiV1(
  override val client: Client,
  override val uriPrefix: String,
  override val backoffs: Stream[Duration] = Backoff.exponentialJittered(1.milliseconds, 5.seconds),
  override val stats: StatsReceiver = DefaultStatsReceiver
) extends KvApi with BaseApi with Closable {
  val kvPrefix = s"$uriPrefix/kv"

  // https://www.consul.io/docs/agent/http/kv.html#single
  def list(
    path: String,
    datacenter: Option[String] = None,
    blockingIndex: Option[String] = None,
    separator: Option[String] = None,
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
    execute[Seq[String]](req, retry)
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
    recurse: Boolean = false,
    retry: Boolean = false
  ): Future[Indexed[Seq[Key]]] = {
    val req = mkreq(
      http.Method.Get,
      s"$kvPrefix$path",
      "index" -> blockingIndex,
      "dc" -> datacenter,
      "recurse" -> (if (recurse) Some(recurse.toString) else None)
    )
    execute[Seq[Key]](req, retry)
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
    execute[Boolean](req, retry).map(_.value)
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
    recurse: Boolean = false,
    retry: Boolean = false
  ): Future[Boolean] = {
    val req = mkreq(
      http.Method.Delete,
      s"$kvPrefix$path",
      "cas" -> cas,
      "recurse" -> (if (recurse) Some(recurse.toString) else None),
      "dc" -> datacenter
    )
    execute[Boolean](req, retry).map(_.value)
  }
}

object Key {
  def apply(key: String, value: String): Key = Key(Some(key), Some(Base64.getEncoder.encodeToString(value.getBytes)))
}

case class Key(
  Key: Option[String],
  Value: Option[String]
) {
  lazy val DecodedValue: Option[String] = Value match {
    case Some(raw: String) => Some(new String(Base64.getDecoder.decode(raw)))
    case None => None
  }
}
