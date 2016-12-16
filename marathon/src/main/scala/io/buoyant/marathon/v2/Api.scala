package io.buoyant.marathon.v2

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.finagle.tracing.Trace
import com.twitter.finagle.{Address, Path, Service, http}
import com.twitter.io.Buf
import com.twitter.util.{Closable, Future, Time, Try}

/**
 * A partial implementation of the Marathon V2 API:
 * https://mesosphere.github.io/marathon/docs/generated/api.html#v2_apps
 */

trait Api {
  def getAppIds(): Future[Api.AppIds]
  def getAddrs(app: Path): Future[Set[Address]]
}

object Api {

  type AppIds = Set[Path]
  type Client = Service[http.Request, http.Response]

  val versionString = "v2"

  case class UnexpectedResponse(rsp: http.Response) extends Throwable

  def apply(client: Client, uriPrefix: String, useHealthCheck: Boolean): Api =
    new AppIdApi(client, s"$uriPrefix/$versionString", useHealthCheck)

  private[v2] def rspToApps(rsp: http.Response): Future[Api.AppIds] =
    rsp.status match {
      case http.Status.Ok =>
        val apps = readJson[AppsRsp](rsp.content).map(_.toApps)
        Future.const(apps)

      case _ => Future.exception(UnexpectedResponse(rsp))
    }

  private[v2] def rspToAddrs(rsp: http.Response, useHealthCheck: Boolean): Future[Set[Address]] =
    rsp.status match {
      case http.Status.Ok =>
        val addrs = readJson[AppRsp](rsp.content).map(_.toAddresses(useHealthCheck))
        Future.const(addrs)
      case _ =>
        Future.exception(UnexpectedResponse(rsp))
    }

  private[this] val mapper = new ObjectMapper with ScalaObjectMapper
  mapper.registerModule(DefaultScalaModule)
  mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  def readJson[T: Manifest](buf: Buf): Try[T] = {
    val Buf.ByteArray.Owned(bytes, begin, end) = Buf.ByteArray.coerce(buf)
    Try(mapper.readValue[T](bytes, begin, end - begin))
  }

  private[this] case class HealthCheckResult(alive: Option[Boolean])

  private[this] case class Task(
    id: Option[String],
    host: Option[String],
    ports: Option[Seq[Int]],
    ipAddresses: Option[Seq[TaskIpAddress]],
    healthCheckResults: Option[Seq[HealthCheckResult]]
  )

  private[this] case class TaskIpAddress(
    ipAddress: Option[String]
  )

  private[this] case class App(
    id: Option[String],
    tasks: Option[Seq[Task]],
    ipAddress: Option[IpAddress]
  ) {
    def appView: Set[AppView] = {
      val ipPort = ipAddress.flatMap(_.discovery).flatMap(_.ports).flatMap(_.headOption).flatMap(_.number)

      tasks match {
        case Some(tasks) =>
          tasks.collect { task =>
            (task, ipPort) match {
              case (Task(_, Some(host), Some(Seq(port, _*)), _, healthCheckResults), _) =>
                AppView(host, port, healthCheckResults)
              case (Task(_, _, _, Some(Seq(TaskIpAddress(Some(host)), _*)), healthCheckResults), Some(port)) =>
                AppView(host, port, healthCheckResults)
            }
          }.toSet
        case _ => Set.empty
      }
    }
  }

  private[this] case class AppView(
    host: String,
    port: Int,
    healthCheckResults: Option[Seq[HealthCheckResult]]
  )

  private[this] case class IpAddress(
    discovery: Option[IpAddressDiscovery]
  )

  private[this] case class IpAddressDiscovery(
    ports: Option[Seq[IpAddressDiscoveryPort]]
  )

  private[this] case class IpAddressDiscoveryPort(
    number: Option[Int]
  )

  private[this] case class AppsRsp(apps: Option[Seq[App]] = None) {

    private[v2] def toApps: Api.AppIds =
      apps match {
        case Some(apps) =>
          apps.collect { case App(Some(id), _, _) => Path.read(id) }.toSet
        case None => Set.empty
      }
  }

  private[this] case class AppRsp(app: Option[App] = None) {

    private[this] def healthy(healthCheckResults: Seq[HealthCheckResult]): Boolean =
      healthCheckResults.forall(_ == HealthCheckResult(Some(true)))

    private[v2] def toAddresses(useHealthCheck: Boolean): Set[Address] =
      app.map(_.appView) match {
        case Some(view) => view.collect {
          case AppView(host, port, _) if !useHealthCheck =>
            Address(host, port)
          case AppView(host, port, Some(healthCheckResults)) if healthy(healthCheckResults) =>
            Address(host, port)
        }
        case _ => Set.empty
      }
  }
}

private class AppIdApi(client: Api.Client, apiPrefix: String, useHealthCheck: Boolean)
  extends Api
  with Closable {

  import Api._

  def close(deadline: Time) = client.close(deadline)

  def getAppIds(): Future[Api.AppIds] = {
    val req = http.Request(s"$apiPrefix/apps")
    Trace.letClear(client(req)).flatMap(rspToApps(_))
  }

  def getAddrs(app: Path): Future[Set[Address]] = {
    val req = http.Request(s"$apiPrefix/apps${app.show}?embed=app.tasks")
    Trace.letClear(client(req)).flatMap(rspToAddrs(_, useHealthCheck))
  }
}
