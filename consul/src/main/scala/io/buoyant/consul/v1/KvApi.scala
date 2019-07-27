package io.buoyant.consul.v1

import java.util.Base64
import com.twitter.finagle.http
import com.twitter.finagle.stats.{DefaultStatsReceiver, StatsReceiver}
import com.twitter.util._

object KvApi {
  def apply(c: Client, backoff: Stream[Duration], enableValueCompression: Boolean = false): KvApi = new KvApi(c, s"/$versionString", backoff, enableValueCompression)
}

class KvApi(
  val client: Client,
  val uriPrefix: String,
  val backoffs: Stream[Duration],
  val enableValueCompression: Boolean,
  val stats: StatsReceiver = DefaultStatsReceiver
) extends BaseApi with Closable {
  val kvPrefix = s"$uriPrefix/kv"

  // https://www.consul.io/docs/agent/http/kv.html#single
  def list(
    path: String,
    datacenter: Option[String] = None,
    blockingIndex: Option[String] = None,
    separator: Option[String] = Some("/"),
    consistency: Option[ConsistencyMode] = None,
    retry: Boolean = false
  ): ApiCall[Indexed[Seq[String]]] = ApiCall(
    req = mkreq(
      http.Method.Get,
      s"$kvPrefix$path",
      consistency,
      "keys" -> Some(true.toString),
      "separator" -> separator,
      "index" -> blockingIndex,
      "dc" -> datacenter
    ),
    call = req => executeJson[Seq[String]](req, retry)
  )

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
    consistency: Option[ConsistencyMode] = None,
    retry: Boolean = false
  ): ApiCall[Indexed[String]] = ApiCall(
    req = mkreq(
      http.Method.Get,
      s"$kvPrefix$path",
      consistency,
      "raw" -> Some(true.toString),
      "index" -> blockingIndex,
      "dc" -> datacenter
    ),
    call = req => executeRaw(req, retry)
      .map(_.mapValue(v => if (enableValueCompression) GZIPStringEncoder.decodeString(v) else v))
  )

  /**
   * Get key(s)
   *
   * https://www.consul.io/docs/agent/http/kv.html#single
   *
   * @param path path to the key, must start with /
   */
  def multiGet(
    path: String,
    datacenter: Option[String] = None,
    blockingIndex: Option[String] = None,
    recurse: Option[Boolean] = None,
    consistency: Option[ConsistencyMode] = None,
    retry: Boolean = false
  ): ApiCall[Indexed[Seq[Key]]] = ApiCall(
    req = mkreq(
      http.Method.Get,
      s"$kvPrefix$path",
      consistency,
      "index" -> blockingIndex,
      "dc" -> datacenter,
      "recurse" -> recurse.map(_.toString)
    ),
    call = req =>
      executeJson[Seq[Key]](req, retry)
        .map(indexed => if (enableValueCompression) indexed.mapValue(_.map(_.decompress)) else indexed)
  )

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
    consistency: Option[ConsistencyMode] = None,
    retry: Boolean = false
  ): ApiCall[Boolean] = ApiCall(
    req = mkreq(
      http.Method.Put,
      s"$kvPrefix$path",
      consistency,
      "cas" -> cas,
      "dc" -> datacenter
    ),
    call = req => {
      req.setContentString(
        if (enableValueCompression)
          GZIPStringEncoder.encode(value.getBytes)
        else value
      )
      executeJson[Boolean](req, retry).map(_.value)
    }
  )

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
    recurse: Option[Boolean] = None,
    consistency: Option[ConsistencyMode] = None,
    retry: Boolean = false
  ): ApiCall[Boolean] = ApiCall(
    req = mkreq(
      http.Method.Delete,
      s"$kvPrefix$path",
      consistency,
      "cas" -> cas,
      "recurse" -> recurse.map(_.toString),
      "dc" -> datacenter
    ),
    call = req => executeJson[Boolean](req, retry).map(_.value)
  )
}

object Key {
  def mk(key: String, value: String): Key = Key(Some(key), Some(Base64.getEncoder.encodeToString(value.getBytes)))
}

case class Key(
  Key: Option[String],
  Value: Option[String]
) {
  lazy val decoded: Option[String] = Value.map { raw => new String(Base64.getDecoder.decode(raw)) }
  def decompress: Key = copy(Value = Value.map(GZIPStringEncoder.decodeString))
}
