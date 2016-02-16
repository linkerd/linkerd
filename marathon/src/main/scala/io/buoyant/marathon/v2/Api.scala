package io.buoyant.marathon.v2

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.finagle.{Service, http}
import com.twitter.logging.Logger
import com.twitter.io.Buf
import com.twitter.util.{Await, Closable, Future, Time, Try}
import java.net.{InetSocketAddress, SocketAddress}

/**
 * A partial implementation of the Marathon V2 API:
 * https://mesosphere.github.io/marathon/docs/generated/api.html#v2_apps
 */

trait Api {
  def getAppIds(): Future[Api.AppIds]
  def getAddrs(app: String): Future[Set[SocketAddress]]
}

object Api {

  type AppIds = Set[String]
  type Client = Service[http.Request, http.Response]

  val versionString = "v2"

  def apply(c: Client, host: String, uriPrefix: String): Api =
    new AppIdApi(c, host, s"$uriPrefix/$versionString")

  private[v2] val log = Logger.get("marathon")

  private[v2] def mkreq(
    path: String,
    host: String
  ): http.Request = {
    val req = http.Request(path)
    req.method = http.Method.Get
    req.host = host
    req
  }

  private[v2] def rspToApps(
    rsp: http.Response
  ): Future[Api.AppIds] =
    rsp.status match {
      case http.Status.Ok =>
        val apps = readJson[AppsRsp](rsp.content).map(_.toApps)
        Future.const(apps)
      case _ =>
        Future.exception(UnexpectedResponse(rsp))
    }

  private[v2] def rspToAddrs(
    rsp: http.Response
  ): Future[Set[SocketAddress]] =
    rsp.status match {
      case http.Status.Ok =>
        val addrs = readJson[AppRsp](rsp.content).map(_.toAddresses)
        Future.const(addrs)
      case _ =>
        Future.exception(UnexpectedResponse(rsp))
    }

  private[this] case class UnexpectedResponse(rsp: http.Response) extends Throwable

  private[this] val mapper = new ObjectMapper with ScalaObjectMapper
  mapper.registerModule(DefaultScalaModule)
  mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  private[this] def readJson[T: Manifest](buf: Buf): Try[T] = {
    val Buf.ByteArray.Owned(bytes, begin, end) = Buf.ByteArray.coerce(buf)
    Try(mapper.readValue[T](bytes, begin, end - begin))
  }

  private[this] case class TaskNode(
    id: Option[String],
    host: Option[String],
    ports: Option[Seq[Int]]
  )

  private[this] case class AppNode(
    id: Option[String],
    tasks: Option[Seq[TaskNode]]
  )

  private[this] case class AppsRsp(
    apps: Option[Seq[AppNode]] = None
  ) {
    def toApps: Api.AppIds =
      apps match {
        case Some(apps) => apps.map { app => app.id.getOrElse("") }.toSet
        case None => Set.empty[String]
      }
  }

  private[this] case class AppRsp(
    app: Option[AppNode] = None
  ) {
    def toAddresses: Set[SocketAddress] =
      app match {
        case Some(AppNode(_, Some(tasks))) =>
          tasks.collect {
            case TaskNode(_, Some(host), Some(Seq(port, _*))) =>
              new InetSocketAddress(host, port)
          }.toSet[SocketAddress]
        case _ => Set.empty[SocketAddress]
      }
  }
}

private[this] class AppIdApi(client: Api.Client, host: String, apiPrefix: String) extends Closable
  with Api {

  import Api._

  def close(deadline: Time) = client.close(deadline)

  def getAppIds(): Future[Api.AppIds] = {
    val req = mkreq(s"$apiPrefix/apps", host)
    client(req).flatMap(rspToApps(_))
  }

  def getAddrs(app: String): Future[Set[SocketAddress]] = {
    val req = mkreq(s"$apiPrefix/apps/$app", host)
    client(req).flatMap(rspToAddrs(_))
  }
}
