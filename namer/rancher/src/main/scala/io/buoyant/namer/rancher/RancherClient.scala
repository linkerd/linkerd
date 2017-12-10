package io.buoyant.namer.rancher

import com.fasterxml.jackson.databind.{ObjectMapper, PropertyNamingStrategy}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

import com.twitter.finagle.{Service, Http, Address, Stack}
import com.twitter.finagle.http
import com.twitter.finagle.service.Retries
import com.twitter.finagle.tracing.NullTracer
import com.twitter.finagle.stats.{Stat, StatsReceiver}

import com.twitter.logging.Logger
import com.twitter.util.Activity.State
import com.twitter.util.{Timer, Var, Activity, Duration, Future, Closable, Return, Throw}

import java.util.concurrent.atomic.AtomicBoolean

case class PortMapping(
  val ip: String,
  val publicPort: Int,
  val privatePort: Int,
  val protocol: String
) {
  // This is a bit of a hack - and should have more error-checking. But it
  // makes the JSON parsing work.
  // If anyone knows how to do a better constructor - or JSON Deserializer,
  // that would be nice
  def this(mapping: String) = this(
    mapping.split(":")(0),
    mapping.split(":")(1).toInt,
    mapping.split(":")(2).split("/")(0).toInt,
    mapping.split(":")(2).split("/")(1)
  )
  override def toString(): String = s"$ip:$privatePort:$publicPort/$protocol"
}

@JsonIgnoreProperties(ignoreUnknown = true)
case class RancherContainer(
  createIndex: Int,
  dns: List[String],
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

class RancherClient(
  log: Logger,
  params: Stack.Params,
  stats: StatsReceiver
)(implicit val timer: Timer) {
  private[this] val objectMapper = new ObjectMapper() with ScalaObjectMapper
  objectMapper.registerModule(DefaultScalaModule)
  objectMapper.setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE)

  private[this] val numVersions = stats.counter("metadata_versions")

  private[this] val maxWait = 30

  private[this] val service = Http.client
    .withStack(Http.client.stack.remove(Retries.Role))
    .withParams(Http.client.params ++ params)
    .withLabel("io.buoyant.namer.rancher.client")
    .withTracer(NullTracer)
    .newService(s"/$$/inet/rancher-metadata/80")

  private[this] val version: Activity[String] =
    Activity(Var.async[State[String]](Activity.Pending) { state =>
      val done = new AtomicBoolean(false)
      val initialized = new AtomicBoolean(false)
      var v = "initial"

      Future.whileDo(!done.get) {
        log.trace("starting version request")

        val request = http.Request(http.Method.Get, s"/2015-12-19/version?wait=true&maxWait=${maxWait}&value=${v}")
        request.host = "rancher-metadata"

        service(request)
          .map { _.getContentString().trim }
          .liftToTry
          .flatMap {
            case Return(newVersion) => {
              if (newVersion != v) {
                log.info("fetched new metadata-version: %s", newVersion)
                v = newVersion
                initialized.set(true)
                numVersions.incr()
                state.update(Activity.Ok(newVersion))
              } else {
                log.trace("metadata-version didn't change. Old: %s, received: %s", v, newVersion)
              }
              Future.Unit
            }
            case Throw(e) => {
              // TODO: This should probably be handled by a exponentail back-off
              // in the Finagle client - but I'm not sure how to achieve that
              log.error("failed to fetch metadata-version. %s", e)
              if (!initialized.get) {
                state.update(Activity.Failed(e))
              }
              Future.sleep(Duration.fromFractionalSeconds(5.0))
            }
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
        .map { objectMapper.readValue[List[RancherContainer]] }
        .onSuccess { containers =>
          log.debug("received %s new containers", containers.size)
        }: Future[List[RancherContainer]]
    }
  }

  def activity: Activity[List[RancherContainer]] = containers.stabilize
}
