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
  private[this] val versionString = "v2"
  private[this] val taskRunning = "TASK_RUNNING"
  private[this] val mapper = new ObjectMapper with ScalaObjectMapper

  mapper.registerModule(DefaultScalaModule)
  mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  type AppIds = Set[Path]
  type Client = Service[http.Request, http.Response]

  case class UnexpectedResponse(rsp: http.Response) extends Throwable

  final case class HealthCheckResult(
    alive: Option[Boolean]
  )

  final case class Task(
    id: Option[String],
    host: Option[String],
    ports: Option[Seq[Int]],
    ipAddresses: Option[Seq[TaskIpAddress]],
    healthCheckResults: Option[Seq[HealthCheckResult]],
    state: Option[String]
  )

  final case class TaskIpAddress(
    ipAddress: Option[String]
  )

  final case class AppIpAddress(
    discovery: Option[AppDiscovery]
  )

  final case class AppDiscovery(
    ports: Option[Seq[AppDiscoveryPort]]
  )

  final case class AppDiscoveryPort(
    name: Option[String],
    number: Option[Int]
  )

  final case class App(
    id: Option[String],
    ipAddress: Option[AppIpAddress],
    tasks: Option[Seq[Task]]
  )

  final case class AppsRsp(
    apps: Option[Seq[App]] = None
  )

  final case class AppRsp(
    app: Option[App] = None
  )

  object discoveryPort {
    def unapply(addr: Option[AppIpAddress]): Option[Int] = {
      addr.flatMap(_.discovery).flatMap(_.ports).flatMap(_.headOption).flatMap(_.number)
    }
  }

  object hostPort {
    def unapply(task: Task): Option[(String, Int)] = {
      for {
        host <- task.host
        ports <- task.ports
        port <- ports.headOption
      } yield (host, port)
    }
  }

  object healthyHostPort {
    def unapply(task: Task): Option[(String, Int)] = {
      if (isHealthy(task)) {
        hostPort.unapply(task)
      } else {
        None
      }
    }
  }

  object ipAddress {
    def unapply(task: Task): Option[String] = {
      task.ipAddresses.flatMap(_.headOption).flatMap(_.ipAddress)
    }
  }

  object healthyIpAddress {
    def unapply(task: Task): Option[String] = {
      if (isHealthy(task)) {
        ipAddress.unapply(task)
      } else {
        None
      }
    }
  }

  def apply(client: Client, uriPrefix: String, useHealthCheck: Boolean): Api =
    new AppIdApi(client, s"$uriPrefix/$versionString", useHealthCheck)

  private[v2] def rspToAppIds(rsp: http.Response): Future[Api.AppIds] =
    rsp.status match {
      case http.Status.Ok =>
        val apps = readJson[AppsRsp](rsp.content).map(toAppIds)
        Future.const(apps)

      case _ => Future.exception(UnexpectedResponse(rsp))
    }

  private[v2] def rspToAddrs(rsp: http.Response, useHealthCheck: Boolean): Future[Set[Address]] =
    rsp.status match {
      case http.Status.Ok =>
        val addrs = readJson[AppRsp](rsp.content).map(toAddresses(_, useHealthCheck))
        Future.const(addrs)
      case _ =>
        Future.exception(UnexpectedResponse(rsp))
    }

  def readJson[T: Manifest](buf: Buf): Try[T] = {
    val Buf.ByteArray.Owned(bytes, begin, end) = Buf.ByteArray.coerce(buf)
    Try(mapper.readValue[T](bytes, begin, end - begin))
  }

  private[this] def toAppIds(appsRsp: AppsRsp): Api.AppIds = {
    appsRsp.apps match {
      case Some(apps) =>
        apps.collect { case App(Some(id), _, _) => Path.read(id) }.toSet
      case None => Set.empty
    }
  }

  private[this] def toAddresses(appRsp: AppRsp, useHealthCheck: Boolean): Set[Address] =
    appRsp.app match {
      case Some(App(_, discoveryPort(port), Some(tasks))) =>
        tasks.collect {
          case ipAddress(host) if !useHealthCheck => Address(host, port)
          case healthyIpAddress(host) => Address(host, port)
        }.toSet
      case Some(App(_, _, Some(tasks))) =>
        tasks.collect {
          case hostPort(host, port) if !useHealthCheck => Address(host, port)
          case healthyHostPort(host, port) => Address(host, port)
        }.toSet
      case _ => Set.empty
    }

  private[this] def isHealthy(task: Task): Boolean =
    task match {
      case Task(_, _, _, _, Some(healthCheckResults), Some(state)) =>
        state == taskRunning &&
          healthCheckResults.forall(_ == HealthCheckResult(Some(true)))
      case _ => false
    }
}

private class AppIdApi(client: Api.Client, apiPrefix: String, useHealthCheck: Boolean)
  extends Api
  with Closable {

  import Api._

  def close(deadline: Time) = client.close(deadline)

  def getAppIds(): Future[Api.AppIds] = {
    val req = http.Request(s"$apiPrefix/apps")
    Trace.letClear(client(req)).flatMap(rspToAppIds)
  }

  def getAddrs(app: Path): Future[Set[Address]] = {
    val req = http.Request(s"$apiPrefix/apps${app.show}?embed=app.tasks")
    Trace.letClear(client(req)).flatMap(rspToAddrs(_, useHealthCheck))
  }
}
