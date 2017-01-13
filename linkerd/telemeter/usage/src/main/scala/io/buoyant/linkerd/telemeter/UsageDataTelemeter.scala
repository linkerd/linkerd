package io.buoyant.linkerd.telemeter

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.annotation.{JsonIgnore, PropertyAccessor}
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.common.metrics.Metrics
import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.http.{Method, Request, Response}
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.NullTracer
import com.twitter.finagle.util.DefaultTimer
import com.twitter.util._
import io.buoyant.admin.Admin
import io.buoyant.linkerd.Linker.{LinkerConfig, LinkerConfigStackParam}
import io.buoyant.linkerd.protocol.HttpConfig
import io.buoyant.linkerd.usage.{Counter, Gauge, Router, UsageMessage}
import io.buoyant.linkerd.{Build, Linker}
import io.buoyant.telemetry.{Telemeter, TelemeterConfig, TelemeterInitializer}
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.JavaConverters._

private[telemeter] object UsageDataTelemeter {
  val DefaultPeriod = 1.hour
  val ContentType = "application/x-protobuf"

  case class Client(
    service: Service[Request, Response],
    config: Linker.LinkerConfig,
    orgId: Option[String]
  ) {
    def apply(metrics: Map[String, Number]): Future[Unit] = {
      val msg = mkUsageMessage(config, orgId, metrics)

      val req = Request(Method.Post, "/")
      req.content = UsageMessage.codec.encodeGrpcMessage(msg)
      req.contentType = ContentType
      service(req).unit
    }
  }

  def mkUsageMessage(
    config: Linker.LinkerConfig,
    orgId: Option[String],
    metrics: Map[String, Number]
  ): UsageMessage =
    UsageMessage(
      pid = Some(java.util.UUID.randomUUID().toString),
      orgid = orgId,
      linkerdversion = Some(Build.load().version),
      containermanager = mkContainerManager,
      osname = Some(System.getProperty("os.name")),
      osversion = Some(System.getProperty("os.version")),
      starttime = None, //TODO
      routers = mkRouters(config),
      namers = mkNamers(config),
      counters = mkCounters(metrics),
      gauges = mkGauges(metrics)
    )

  def mkContainerManager: Option[String] =
    sys.env.get("MESOS_TASK_ID").map(_ => "mesos")

  def mkNamers(config: Linker.LinkerConfig): Seq[String] =
    config.namers.getOrElse(Seq()).map(_.kind)

  def mkGauges(metrics: Map[String, Number]): Seq[Gauge] =
    Seq(
      Gauge(Some("jvm_mem"), metrics.get("jvm/mem/current/used").map(_.doubleValue())),
      Gauge(Some("jvm/gc/msec"), metrics.get("jvm/mem/current/used").map(_.doubleValue()))
    )

  val RequestPattern = s"""^*/srv/*/requests$$""".r
  def mkCounters(metrics: Map[String, Number]): Seq[Counter] =
    metrics.filter({
      case (RequestPattern(key), value) => true
      case _ => false
    }).map({
      case (k, v: Number) => Counter(Some("srv_requests"), Some(v.longValue()))
    }).toSeq

  def mkRouters(config: Linker.LinkerConfig): Seq[Router] =
    config.routers.map(r => {

      val identifiers = r match {
        case httpRouter: HttpConfig =>
          httpRouter.identifier.getOrElse(Seq()).map(_.kind)
        case _ => Seq()
      }

      val transformers = r.interpreter.transformers.getOrElse(Seq()).map(_.kind)

      Router(
        protocol = Some(r.protocol.configId),
        interpreter = Some(r.interpreter.kind),
        identifiers = identifiers,
        transformers = transformers
      )
    })

  class UsageDataHandler(
    service: Service[Request, Response],
    config: Linker.LinkerConfig,
    orgId: Option[String],
    registry: Metrics
  ) extends Admin.Handler {

    def apply(request: Request): Future[Response] = {
      val msg: UsageMessage = mkUsageMessage(config, orgId, registry.sample().asScala.toMap)
      val rsp = Response()
      rsp.contentType = "application/json"
      rsp.contentString = mapper.writeValueAsString(msg)
      Future.value(rsp)
    }
  }

  val mapper = new ObjectMapper(new JsonFactory) with ScalaObjectMapper
  mapper.registerModule(DefaultScalaModule)
  mapper.setSerializationInclusion(Include.NON_ABSENT)
  mapper.setVisibility(PropertyAccessor.ALL, Visibility.PUBLIC_ONLY)
}

class UsageDataTelemeter(
  metricsDst: Name,
  config: Linker.LinkerConfig,
  registry: com.twitter.common.metrics.Metrics,
  orgId: Option[String]
) extends Telemeter with Admin.WithHandlers {
  import UsageDataTelemeter._

  val tracer = NullTracer
  val stats = NullStatsReceiver

  private[this] val metricsService = Http.client.newService(metricsDst, "usageData")
  private[this] val started = new AtomicBoolean(false)

  val adminHandlers: Admin.Handlers = Seq(
    "/admin/metrics/usage" -> new UsageDataHandler(metricsService, config, orgId, registry)
  )

  def sample(registry: Metrics) = registry.sample().asScala.toMap

  // Only run at most once.
  def run(): Closable with Awaitable[Unit] =
    if (started.compareAndSet(false, true)) run0()
    else Telemeter.nopRun

  private[this] def run0() = {
    val client = Client(metricsService, config, orgId)
    val _ = client(sample(registry))

    val task = DefaultTimer.twitter.schedule(DefaultPeriod) {
      val _ = client(sample(registry))
    }

    val closer = Closable.sequence(
      task,
      Closable.all(metricsService)
    )

    new Closable with CloseAwaitably {
      def close(deadline: Time) = closeAwaitably {
        closer.close(deadline)
      }
    }
  }
}

case class UsageDataTelemeterConfig(
  orgId: Option[String]
) extends TelemeterConfig {

  @JsonIgnore
  def mk(params: Stack.Params): UsageDataTelemeter = {
    val config = params[LinkerConfigStackParam] match {
      case LinkerConfigStackParam(c) => c
      case e => LinkerConfig(None, Seq(), None, None, None)
    }

    val DefaultPath = "/$/inet/130.211.174.30/80"

    new UsageDataTelemeter(
      Name(DefaultPath),
      config,
      com.twitter.common.metrics.Metrics.root,
      orgId
    )
  }
}

object UsageDataTelemeterInitializer extends UsageDataTelemeterInitializer

class UsageDataTelemeterInitializer extends TelemeterInitializer {
  type Config = UsageDataTelemeterConfig
  def configClass = classOf[UsageDataTelemeterConfig]
  override def configId = "io.l5d.usage"
}
