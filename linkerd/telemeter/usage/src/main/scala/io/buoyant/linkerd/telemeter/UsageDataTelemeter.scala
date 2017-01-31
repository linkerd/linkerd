package io.buoyant.linkerd.telemeter

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.annotation.{JsonIgnore, PropertyAccessor}
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.google.protobuf.CodedOutputStream
import com.twitter.common.metrics.Metrics
import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.http.{MediaType, Method, Request, Response}
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.NullTracer
import com.twitter.finagle.util.DefaultTimer
import com.twitter.io.Buf
import com.twitter.logging.Logger
import com.twitter.util._
import io.buoyant.admin.Admin
import io.buoyant.admin.Admin.Handler
import io.buoyant.linkerd.Linker.param.LinkerConfig
import io.buoyant.linkerd.protocol.HttpConfig
import io.buoyant.linkerd.usage.{Counter, Gauge, Router, UsageMessage}
import io.buoyant.linkerd.{Build, Linker}
import io.buoyant.telemetry.{Telemeter, TelemeterConfig, TelemeterInitializer}
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.JavaConverters._

private[telemeter] object UsageDataTelemeter {
  val DefaultPeriod = 1.hour
  val ContentType = "application/octet-stream"

  case class Client(
    service: Service[Request, Response],
    config: Linker.LinkerConfig,
    pid: String,
    orgId: Option[String]
  ) {
    def apply(metrics: Map[String, Number]): Future[Unit] = {
      val msg = mkUsageMessage(config, pid, orgId, metrics)
      log.debug(msg.toString)

      val sz = UsageMessage.codec.sizeOf(msg)
      val bb0 = ByteBuffer.allocate(sz)
      val bb = bb0.duplicate()
      UsageMessage.codec.encode(msg, CodedOutputStream.newInstance(bb))

      val req = Request(Method.Post, "/")
      req.host = "stats.buoyant.io"
      req.content = Buf.ByteBuffer.Owned(bb0)
      req.contentType = ContentType
      service(req).map(r => log.debug(r.contentString)).unit
    }
  }

  def mkUsageMessage(
    config: Linker.LinkerConfig,
    pid: String,
    orgId: Option[String],
    metrics: Map[String, Number]
  ): UsageMessage =
    UsageMessage(
      pid = Some(pid),
      orgId = orgId,
      linkerdVersion = Some(Build.load().version),
      containerManager = mkContainerManager,
      osName = Some(System.getProperty("os.name")),
      osVersion = Some(System.getProperty("os.version")),
      startTime = None, //TODO
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

  val RequestPattern = "^.*/srv/.*/requests$".r
  def mkCounters(metrics: Map[String, Number]): Seq[Counter] =
    metrics.collect {
      case (RequestPattern(), v) => Counter(Some("srv_requests"), Some(v.longValue()))
    }.toSeq

  def mkRouters(config: Linker.LinkerConfig): Seq[Router] =
    config.routers.map { r =>

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
    }

  class UsageDataHandler(
    service: Service[Request, Response],
    config: Linker.LinkerConfig,
    pid: String,
    orgId: Option[String],
    registry: Metrics
  ) extends Service[Request, Response] {

    def apply(request: Request): Future[Response] = {
      val msg: UsageMessage = mkUsageMessage(config, pid, orgId, registry.sample().asScala.toMap)
      val rsp = Response()
      rsp.contentType = MediaType.Json
      rsp.contentString = mapper.writeValueAsString(msg)
      Future.value(rsp)
    }
  }

  val mapper = new ObjectMapper(new JsonFactory) with ScalaObjectMapper
  mapper.registerModule(DefaultScalaModule)
  mapper.setSerializationInclusion(Include.NON_ABSENT)
  mapper.setVisibility(PropertyAccessor.ALL, Visibility.PUBLIC_ONLY)

  private val log = Logger.get(getClass.getName)
}

/**
 * A telemeter that relies on LinkerConfig and Metrics registry params
 * to send anonymous usage data to metricsDst on an hourly basis.
 *
 * Defines neither its own tracer nor its own stats receiver.
 */
class UsageDataTelemeter(
  metricsDst: Name,
  withTls: Boolean,
  config: Linker.LinkerConfig,
  registry: com.twitter.common.metrics.Metrics,
  orgId: Option[String],
  dryRun: Boolean
) extends Telemeter with Admin.WithHandlers {
  import UsageDataTelemeter._

  val tracer = NullTracer
  val stats = NullStatsReceiver
  private[this] val httpClient = if (withTls) Http.client.withTls("stats.buoyant.io") else Http.client
  private[this] val metricsService = httpClient.newService(metricsDst, "usageData")
  private[this] val started = new AtomicBoolean(false)
  private[this] val pid = java.util.UUID.randomUUID().toString
  log.info(s"connecting to usageData proxy at $metricsDst")
  val client = Client(metricsService, config, pid, orgId)

  val adminHandlers = Seq(
    Handler("/admin/metrics/usage", new UsageDataHandler(metricsService, config, pid, orgId, registry))
  )

  private[this] def sample(registry: Metrics): Map[String, Number] = registry.sample().asScala.toMap

  // Only run at most once.
  def run(): Closable with Awaitable[Unit] =
    if (!dryRun && started.compareAndSet(false, true)) run0()
    else Telemeter.nopRun

  private[this] def run0() = {
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
  orgId: Option[String],
  dryRun: Option[Boolean]
) extends TelemeterConfig {

  @JsonIgnore
  def mk(params: Stack.Params): UsageDataTelemeter = {
    val LinkerConfig(config) = params[LinkerConfig]

    new UsageDataTelemeter(
      Name.bound(Address("stats.buoyant.io", 443)),
      withTls = true,
      config,
      com.twitter.common.metrics.Metrics.root,
      orgId,
      dryRun.getOrElse(false)
    )
  }
}

object UsageDataTelemeterInitializer extends UsageDataTelemeterInitializer

class UsageDataTelemeterInitializer extends TelemeterInitializer {
  type Config = UsageDataTelemeterConfig
  def configClass = classOf[UsageDataTelemeterConfig]
  override def configId = "io.l5d.usage"
}
