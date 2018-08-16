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
  def getAddrs(app: Path, watchState: Option[WatchState] = None): Future[Set[Address]]
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

  final case class HealthCheck()

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
    healthChecks: Option[Seq[HealthCheck]],
    tasks: Option[Seq[Task]]
  )

  final case class AppsRsp(
    apps: Option[Seq[App]] = None
  )

  final case class AppRsp(
    app: Option[App] = None
  )

  final case class TaskWithHealthCheckInfo(
    task: Task,
    numberOfHealthChecks: Int
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
    def unapply(extendedTask: TaskWithHealthCheckInfo): Option[(String, Int)] = {
      if (isHealthy(extendedTask)) {
        hostPort.unapply(extendedTask.task)
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
    def unapply(extendedTask: TaskWithHealthCheckInfo): Option[String] = {
      if (isHealthy(extendedTask)) {
        ipAddress.unapply(extendedTask.task)
      } else {
        None
      }
    }
  }

  object healthCheckCount {
    def unapply(healthChecks: Option[Seq[HealthCheck]]): Option[Int] =
      healthChecks match {
        case Some(healthCheckSeq) => Some(healthCheckSeq.size)
        case None => Some(0)
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

  private[v2] def rspToAppRsp(rsp: http.Response): Future[AppRsp] =
    rsp.status match {
      case http.Status.Ok =>
        val addrs = readJson[AppRsp](rsp.content)
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
        apps.collect { case App(Some(id), _, _, _) => Path.read(id.toLowerCase) }.toSet
      case None => Set.empty
    }
  }

  private[v2] def toAddresses(appRsp: AppRsp, useHealthCheck: Boolean): Set[Address] =
    appRsp.app match {
      case Some(App(_, discoveryPort(port), healthCheckCount(count), Some(tasks))) =>
        if (!useHealthCheck)
          tasks.collect { case ipAddress(host) => Address(host, port) }.toSet
        else
          tasks.map(TaskWithHealthCheckInfo(_, count)).collect {
            case healthyIpAddress(host) => Address(host, port)
          }.toSet
      case Some(App(_, _, healthCheckCount(count), Some(tasks))) =>
        if (!useHealthCheck)
          tasks.collect { case hostPort(host, port) => Address(host, port) }.toSet
        else
          tasks.map(TaskWithHealthCheckInfo(_, count)).collect {
            case healthyHostPort(host, port) => Address(host, port)
          }.toSet
      case _ => Set.empty
    }

  private[this] def isHealthy(extendedTask: TaskWithHealthCheckInfo): Boolean = {
    extendedTask.task match {
      case Task(_, _, _, _, Some(healthCheckResults), Some(state)) if extendedTask.numberOfHealthChecks > 0 =>
        state == taskRunning &&
          healthCheckResults.count(_ == HealthCheckResult(Some(true))) >= extendedTask.numberOfHealthChecks
      case Task(_, _, _, _, _, Some(state)) =>
        state == taskRunning && extendedTask.numberOfHealthChecks == 0
      case _ => false
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
    Trace.letClear(client(req)).flatMap(rspToAppIds)
  }

  def getAddrs(app: Path, watchState: Option[WatchState] = None): Future[Set[Address]] = {
    val req = http.Request(s"$apiPrefix/apps${app.show}?embed=app.tasks")
    watchState.foreach(_.recordApiCall(req))
    Trace.letClear(client(req)).flatMap { rep =>
      rspToAppRsp(rep).map { appsResp =>
        watchState.foreach(_.recordResponse(appsResp))
        toAddresses(appsResp, useHealthCheck)
      }
    }
  }
}
