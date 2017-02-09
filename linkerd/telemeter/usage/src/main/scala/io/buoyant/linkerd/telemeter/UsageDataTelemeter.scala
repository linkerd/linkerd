package io.buoyant.linkerd.telemeter

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.annotation.{JsonIgnore, PropertyAccessor}
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.google.protobuf.CodedOutputStream
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
import io.buoyant.telemetry._
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.{Date, TimeZone}
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
    def apply(metrics: MetricsTree): Future[Unit] = {
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
    metrics: MetricsTree
  ): UsageMessage =
    UsageMessage(
      pid = Some(pid),
      orgId = orgId,
      linkerdVersion = Some(Build.load().version),
      containerManager = mkContainerManager,
      osName = Some(System.getProperty("os.name")),
      osVersion = Some(System.getProperty("os.version")),
      startTime = mkStartTime(metrics),
      routers = mkRouters(config),
      namers = mkNamers(config),
      counters = mkCounters(metrics),
      gauges = mkGauges(metrics)
    )

  def mkContainerManager: Option[String] =
    sys.env.get("MESOS_TASK_ID").map(_ => "mesos")

  def mkStartTime(metrics: MetricsTree): Option[String] = {
    val tz = TimeZone.getTimeZone("UTC")
    val df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'")
    df.setTimeZone(tz)
    val uptime = gaugeValue(metrics.resolve(Seq("jvm", "uptime")).metric).map(_.toLong).getOrElse(0l)
    Some(df.format(new Date((System.currentTimeMillis() - uptime).toInt)))
  }

  def mkNamers(config: Linker.LinkerConfig): Seq[String] =
    config.namers.getOrElse(Seq()).map(_.kind)

  def gaugeValue(metric: Metric): Option[Double] = metric match {
    case g: Metric.Gauge => Some(g.get)
    case _ => None
  }

  def mkGauges(metrics: MetricsTree): Seq[Gauge] =
    Seq(
      Gauge(Some("jvm_mem"), gaugeValue(metrics.resolve(Seq("jvm", "mem", "current", "used")).metric)),
      Gauge(Some("jvm/gc/msec"), gaugeValue(metrics.resolve(Seq("jvm", "mem", "current", "used")).metric)),
      Gauge(Some("jvm/uptime"), gaugeValue(metrics.resolve(Seq("jvm", "uptime")).metric))
    )

  def counterValue(metric: Metric): Option[Long] = metric match {
    case c: Metric.Counter => Some(c.get)
    case _ => None
  }

  def mkCounters(metrics: MetricsTree): Seq[Counter] = {
    val counters = for {
      router <- metrics.resolve(Seq("rt")).children.values
      server <- router.resolve(Seq("srv")).children.values
      counter <- counterValue(server.resolve(Seq("requests")).metric)
    } yield Counter(Some("srv_requests"), Some(counter))
    counters.toSeq
  }

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
    metrics: MetricsTree
  ) extends Service[Request, Response] {

    def apply(request: Request): Future[Response] = {
      val msg: UsageMessage = mkUsageMessage(config, pid, orgId, metrics)
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
  metrics: MetricsTree,
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
    Handler("/admin/metrics/usage", new UsageDataHandler(metricsService, config, pid, orgId, metrics))
  )

  // Only run at most once.
  def run(): Closable with Awaitable[Unit] =
    if (!dryRun && started.compareAndSet(false, true)) run0()
    else Telemeter.nopRun

  private[this] def run0() = {
    val _ = client(metrics)

    val task = DefaultTimer.twitter.schedule(DefaultPeriod) {
      val _ = client(metrics)
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
      params[MetricsTree],
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
