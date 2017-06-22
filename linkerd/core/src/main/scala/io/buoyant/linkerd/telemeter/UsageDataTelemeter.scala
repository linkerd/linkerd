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
import com.twitter.finagle.service.RetryBudget
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.NullTracer
import com.twitter.finagle.util.DefaultTimer
import com.twitter.io.Buf
import com.twitter.logging.Logger
import com.twitter.util._
import io.buoyant.admin.{Admin, Build}
import io.buoyant.admin.Admin.Handler
import io.buoyant.linkerd.Linker.param.LinkerConfig
import io.buoyant.linkerd.usage.{Counter, Gauge, Router, UsageMessage}
import io.buoyant.linkerd.Linker
import io.buoyant.telemetry._
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.{Date, TimeZone}
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.JavaConverters._
import scala.language.reflectiveCalls
import scala.util.Random

private[telemeter] object UsageDataTelemeter {
  val DefaultPeriod = 1.hour
  val ContentType = "application/octet-stream"

  case class Client(
    service: Service[Request, Response],
    config: Linker.LinkerConfig,
    pid: String,
    orgId: Option[String]
  ) {

    private[this] val blackholeMonitor = Monitor.mk {
      case _ => true
    }

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
      Monitor.using(blackholeMonitor) {
        service(req).map(r => log.debug(r.contentString)).unit
      }
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
      linkerdVersion = Some(Build.load("/io/buoyant/linkerd/build.properties").version),
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
    Some(df.format(new Date(System.currentTimeMillis() - uptime)))
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
      Gauge(Some("jvm/uptime"), gaugeValue(metrics.resolve(Seq("jvm", "uptime")).metric)),
      Gauge(Some("jvm/num_cpus"), gaugeValue(metrics.resolve(Seq("jvm", "num_cpus")).metric))
    )

  def counterValue(metric: Metric): Option[Long] = metric match {
    case c: Metric.Counter => Some(c.get)
    case _ => None
  }

  def mkCounters(metrics: MetricsTree): Seq[Counter] = {
    val counters = for {
      router <- metrics.resolve(Seq("rt")).children.values
      server <- router.resolve(Seq("server")).children.values
      counter <- counterValue(server.resolve(Seq("requests")).metric)
    } yield Counter(Some("srv_requests"), Some(counter))
    counters.toSeq
  }

  type HttpRouter = { def identifier: Option[Seq[{ def kind: String }]] }

  def mkRouters(config: Linker.LinkerConfig): Seq[Router] =
    config.routers.map { r =>

      val identifiers = try {
        val httpRouter = r.asInstanceOf[HttpRouter]
        httpRouter.identifier.getOrElse(Seq()).map(_.kind)
      } catch {
        case e: NoSuchMethodException => Seq()
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
  val orgId: Option[String]
)(implicit timer: Timer) extends Telemeter with Admin.WithHandlers {
  import UsageDataTelemeter._

  val tracer = NullTracer
  val stats = NullStatsReceiver

  private[this] val baseHttpClient = Http.client
    .withStatsReceiver(NullStatsReceiver)
    .withSessionQualifier.noFailFast
    .withSessionQualifier.noFailureAccrual
    .withRetryBudget(RetryBudget.Empty)
  private[this] val httpClient = if (withTls) baseHttpClient.withTls("stats.buoyant.io") else baseHttpClient
  private[this] val metricsService = httpClient.newService(metricsDst, "usageData")
  private[this] val started = new AtomicBoolean(false)
  private[this] val pid = java.util.UUID.randomUUID().toString
  log.info(s"connecting to usageData proxy at $metricsDst")
  val client = Client(metricsService, config, pid, orgId)

  val adminHandlers = Seq(
    Handler("/admin/metrics/usage", new UsageDataHandler(metricsService, config, pid, orgId, metrics))
  )

  def jitter(i: Duration): Duration = (Random.nextGaussian() * i.inSeconds).toInt.seconds

  // Only run at most once.
  def run(): Closable with Awaitable[Unit] =
    if (started.compareAndSet(false, true)) run0()
    else Telemeter.nopRun

  private[this] def run0() = {
    Future.sleep(jitter(1.minute)).before(client(metrics))

    val task = timer.schedule(DefaultPeriod.plus(jitter(1.minute))) {
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
  orgId: Option[String] = None,
  enabled: Option[Boolean] = None
) {

  @JsonIgnore
  def mk(params: Stack.Params): Telemeter =
    if (enabled.getOrElse(true)) {
      val LinkerConfig(config) = params[LinkerConfig]
      implicit val timer = DefaultTimer

      new UsageDataTelemeter(
        Name.bound(Address("stats.buoyant.io", 443)),
        withTls = true,
        config,
        params[MetricsTree],
        orgId
      )
    } else {
      NullTelemeter
    }
}
