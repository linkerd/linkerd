package io.buoyant.k8s.istio

import com.fasterxml.jackson.core.{JsonGenerator, JsonParser}
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind._
import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.util.DefaultTimer
import com.twitter.util._
import istio.proxy.v1.config.StringMatch.OneofMatchType
import istio.proxy.v1.config.{RouteRule, StringMatch}

/**
 * ApiserverClient is used to get route-rules from the Istio-Pilot apiserver.
 * https://github.com/istio/pilot/blob/master/apiserver/apiserver.go
 */
class ApiserverClient(
  client: Service[Request, Response],
  pollInterval: Duration
)(implicit timer: Timer = DefaultTimer) extends PollingApiClient(client) {
  import ApiserverClient._

  val Url = "/v1alpha1/config/route-rule"

  def getRouteRules: Future[Seq[RouteRuleConfig]] =
    get[Seq[RouteRuleConfig]](Url)
  val watchRouteRules: Activity[Seq[RouteRuleConfig]] =
    watch[Seq[RouteRuleConfig]](Url, pollInterval)

  override def registerModules(mapper: ObjectMapper): Unit = {
    val _ = mapper.registerModule(mkModule())
  }

  private[this] def mkModule() = {
    val module = new SimpleModule

    module.addSerializer(classOf[StringMatch], new JsonSerializer[StringMatch] {
      override def serialize(sm: StringMatch, json: JsonGenerator, p: SerializerProvider) {
        json.writeStartObject()

        sm.`matchType` match {
          case Some(OneofMatchType.Prefix(pfx)) => json.writeStringField("prefix", pfx)
          case Some(OneofMatchType.Exact(value)) => json.writeStringField("exact", value)
          case Some(OneofMatchType.Regex(r)) => json.writeStringField("regex", r)
          case _ =>
        }

        json.writeEndObject()
      }
    })
    module.addDeserializer(classOf[StringMatch], new JsonDeserializer[StringMatch] {
      override def deserialize(json: JsonParser, ctx: DeserializationContext): StringMatch = {
        assert(json.currentToken().isStructStart)
        val fieldName = json.nextFieldName()
        val fieldValue = json.nextTextValue()
        val stringMatch = fieldName match {
          case "prefix" => Some(OneofMatchType.Prefix(fieldValue))
          case "exact" => Some(OneofMatchType.Exact(fieldValue))
          case "regex" => Some(OneofMatchType.Regex(fieldValue))
          case _ => None
        }

        assert(json.nextToken().isStructEnd)
        StringMatch(stringMatch)
      }
    })

    module
  }
}

object ApiserverClient {
  trait IstioConfig[T] {
    def `type`: Option[String]
    def name: Option[String]
    def spec: Option[T]
  }

  case class RouteRuleConfig(
    `type`: Option[String],
    name: Option[String],
    spec: Option[RouteRule]
  ) extends IstioConfig[RouteRule]
}
