package io.buoyant.k8s

import com.fasterxml.jackson.annotation.{JsonCreator, JsonIgnore, JsonProperty}
import com.fasterxml.jackson.core.{JsonParser, JsonToken}
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.{DeserializationContext, JsonDeserializer, JsonMappingException}
import com.twitter.finagle.buoyant.TlsClientPrep
import com.twitter.finagle.http
import scala.annotation.tailrec

//TODO: when we upgrade to Jackson 2.5+, we should be able to make use of an @Creator
//      annotation to allow parsing from boolean or object rather than this
//      custom deserializer.
class TlsClientConfigDeserializer
  extends StdDeserializer[TlsClientConfig](classOf[TlsClientConfig]) {

  def deserialize(jp: JsonParser, ctxt: DeserializationContext): TlsClientConfig = {
    def unexpectedToken(t: JsonToken): JsonMappingException =
      new JsonMappingException(s"Unexpected token parsing TLS config: $t")

    val startToken = jp.getCurrentToken
    if (startToken.isBoolean) {
      new TlsClientConfig(enabled = Some(jp.getBooleanValue))
    } else if (startToken.isStructStart) {
      @tailrec def parseTlsObj(accum: TlsClientConfig): TlsClientConfig = {
        val attr = jp.nextValue
        if (attr.isStructEnd) return accum
        jp.getCurrentName match {
          case "enabled" =>
            parseTlsObj(accum.copy(enabled = Some(_parseBoolean(jp, ctxt))))
          case "strict" =>
            parseTlsObj(accum.copy(strict = Some(_parseBoolean(jp, ctxt))))
          case "caCertPath" =>
            parseTlsObj(accum.copy(caCertPath = Some(_parseString(jp, ctxt))))
          case _ =>
            throw unexpectedToken(attr)
        }
      }

      parseTlsObj(TlsClientConfig(None, None, None))
    } else {
      throw unexpectedToken(startToken)
    }
  }
}

@JsonDeserialize(using = classOf[TlsClientConfigDeserializer])
case class TlsClientConfig(
  enabled: Option[Boolean] = None,
  strict: Option[Boolean] = None,
  caCertPath: Option[String] = None
) {

  @JsonIgnore
  def tlsClientPrep(host: String): TlsClientPrep.Module[http.Request, http.Response] = {
    if (enabled getOrElse TlsClientConfig.DefaultEnabled) {
      if (strict getOrElse TlsClientConfig.DefaultStrict) {
        TlsClientPrep.static(host, caCertPath orElse Some(TlsClientConfig.DefaultCert))
      } else {
        TlsClientPrep.withoutCertificateValidation
      }
    } else {
      TlsClientPrep.disable
    }
  }
}

object TlsClientConfig {
  val DefaultEnabled = true
  val DefaultStrict = true
  val DefaultCert = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"
}
