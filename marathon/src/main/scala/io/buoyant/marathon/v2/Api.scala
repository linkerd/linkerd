package io.buoyant.marathon.v2

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.finagle.tracing.Trace
import com.twitter.finagle.{Address, Path, Service, SimpleFilter, http}
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

  private[this] case class SetHost(host: String)
    extends SimpleFilter[http.Request, http.Response] {

    def apply(req: http.Request, service: Service[http.Request, http.Response]) = {
      req.host = host
      service(req)
    }
  }

  def apply(client: Client, host: String, uriPrefix: String): Api =
    new AppIdApi(SetHost(host).andThen(client), s"$uriPrefix/$versionString")

  private[v2] def rspToApps(rsp: http.Response): Future[Api.AppIds] =
    rsp.status match {
      case http.Status.Ok =>
        val apps = readJson[AppsRsp](rsp.content).map(_.toApps)
        Future.const(apps)

      case _ => Future.exception(UnexpectedResponse(rsp))
    }

  private[v2] def rspToAddrs(rsp: http.Response): Future[Set[Address]] =
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

  private[this] case class Task(
    id: Option[String],
    host: Option[String],
    ports: Option[Seq[Int]]
  )

  private[this] case class App(
    id: Option[String],
    tasks: Option[Seq[Task]]
  )

  private[this] case class AppsRsp(apps: Option[Seq[App]] = None) {

    def toApps: Api.AppIds =
      apps match {
        case Some(apps) =>
          apps.collect { case App(Some(id), _) => Path.read(id) }.toSet
        case None => Set.empty
      }
  }

  private[this] case class AppRsp(app: Option[App] = None) {

    def toAddresses: Set[Address] =
      app match {
        case Some(App(_, Some(tasks))) =>
          tasks.collect {
            case Task(_, Some(host), Some(Seq(port, _*))) =>
              Address(host, port)
          }.toSet

        case _ => Set.empty
      }
  }
}

private class AppIdApi(client: Api.Client, apiPrefix: String)
  extends Api
  with Closable {

  import Api._

  def close(deadline: Time) = client.close(deadline)

  def getAppIds(): Future[Api.AppIds] = {
    val req = http.Request(s"$apiPrefix/apps")
    Trace.letClear(client(req)).flatMap(rspToApps(_))
  }

  def getAddrs(app: Path): Future[Set[Address]] = {
    val req = http.Request(s"$apiPrefix/apps${app.show}")
    Trace.letClear(client(req)).flatMap(rspToAddrs(_))
  }
}
