package io.buoyant.namer.rancher

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.{DeserializationContext, ObjectMapper, PropertyNamingStrategy}
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.finagle.{Service, Http, Address, Stack}
import com.twitter.finagle.http
import com.twitter.finagle.service.Retries
import com.twitter.finagle.stats.{Stat, StatsReceiver}
import com.twitter.finagle.tracing.NullTracer
import com.twitter.logging.Logger
import com.twitter.util._
import com.twitter.util.Activity.State
import java.util.concurrent.atomic.AtomicBoolean
import scala.reflect.{ClassTag, classTag}

case class PortMapping(
  ip: String,
  publicPort: Int,
  privatePort: Int,
  protocol: String
) {
  override def toString(): String = s"$ip:$privatePort:$publicPort/$protocol"
}
class PortMappingDeserializer extends StdDeserializer[PortMapping](RancherParser.jClass[PortMapping]) {
  private[this] val Mapping = raw"([\.0-9]+):([0-9]+):([0-9]+)/(tcp|udp)".r
  override def deserialize(jp: JsonParser, ctx: DeserializationContext): PortMapping = {
    val mapping = _parseString(jp, ctx)
    mapping match {
      case Mapping(ip, privatePort, publicPort, protocol) =>
        PortMapping(ip, privatePort.toInt, publicPort.toInt, protocol)
      case _ =>
        throw ctx.mappingException(s"Failed to understand ip-binding: $mapping")
    }
  }
}

@JsonIgnoreProperties(ignoreUnknown = true)
case class RancherContainer(
  createIndex: Int,
  ips: List[String],
  primaryIp: String,
  labels: Map[String, String],
  name: String,
  ports: List[PortMapping],
  serviceName: String,
  stackName: String,
  state: String
) {
  def toAddr(port: Int): Option[Address] = state match {
    case "running" => Some(Address(primaryIp, port))
    case _ => None
  }
}

object RancherParser {
  private[rancher] def jClass[T: ClassTag] = classTag[T].runtimeClass.asInstanceOf[Class[T]]

  private[this] val customTypes = new SimpleModule("RancherClient custom types")
  customTypes.addDeserializer(
    RancherParser.jClass[PortMapping],
    new PortMappingDeserializer()
  )
  private[this] val objectMapper = new ObjectMapper() with ScalaObjectMapper
  objectMapper.registerModule(DefaultScalaModule)
  objectMapper.registerModule(customTypes)
  objectMapper.setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE)

  def apply() = objectMapper
}

class RancherClient(
  maxWait: Int,
  log: Logger,
  params: Stack.Params,
  stats: StatsReceiver
)(implicit val timer: Timer) {
  private[this] val numVersions = stats.counter("metadata_versions")

  private[this] val service = Http.client
    .withStack(Http.client.stack.remove(Retries.Role))
    .withParams(Http.client.params ++ params)
    .withLabel("io.buoyant.namer.rancher.client")
    .withTracer(NullTracer)
    .newService(s"/$$/inet/rancher-metadata/80")

  private[this] val version: Activity[String] =
    Activity(Var.async[State[String]](Activity.Pending) { state =>
      val done = new AtomicBoolean(false)
      @volatile var v: Option[String] = None

      Future.whileDo(!done.get) {
        log.trace("starting version request")

        val request = http.Request(http.Method.Get, s"/2015-12-19/version?wait=true&maxWait=${maxWait}&value=${v.getOrElse("initial")}")
        request.host = "rancher-metadata"

        service(request)
          .map { _.getContentString().trim }
          .transform {
            case Return(newVersion) if !v.contains(newVersion) =>
              log.info("fetched new metadata-version: %s", newVersion)
              v = Some(newVersion)
              numVersions.incr()
              state.update(Activity.Ok(newVersion))
              Future.Unit
            case Return(newVersion) if v.contains(newVersion) =>
              log.trace("metadata-version didn't change. Old: %s, received: %s", v.get, newVersion)
              Future.Unit
            case Throw(e) =>
              // TODO: This should probably be handled by a exponentail back-off
              // in the Finagle client - but I'm not sure how to achieve that
              log.error("failed to fetch metadata-version. %s", e)
              if (!v.isEmpty) {
                // We have previously managed to get a version, but now we can't
                // so we set mark the activity as failed
                state.update(Activity.Failed(e))
              }
              Future.sleep(Duration.fromFractionalSeconds(5.0))
          }
      }

      Closable.make { _ =>
        done.set(true)
        Future.Unit
      }
    })

  private[this] val containers: Activity[List[RancherContainer]] = version.flatMap { version =>
    Activity.future {
      val request = http.Request(http.Method.Get, "/2015-12-19/containers")
      request.host = "rancher-metadata"
      request.accept = "application/json"

      service(request)
        .map { _.getContentString() }
        .map { RancherParser().readValue[List[RancherContainer]] }
        .onSuccess { containers =>
          log.debug("received %s new containers", containers.size)
        }: Future[List[RancherContainer]]
    }
  }

  def activity: Activity[List[RancherContainer]] = containers.stabilize
}
