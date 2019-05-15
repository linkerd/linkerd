package io.buoyant.namerd.iface.mesh

import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.finagle.buoyant.h2.{Request, Response}
import com.twitter.finagle.context.RemoteInfo
import com.twitter.logging.Logger
import com.twitter.util.{Future, Time, TimeFormat}
import java.net.InetSocketAddress

case class MeshAccessLogger(log: Logger) extends SimpleFilter[Request, Response] {

  override def apply(
    request: Request,
    service: Service[Request, Response]
  ): Future[Response] = {
    val reqHeaders = request.headers
    val remoteHost = RemoteInfo.Upstream.addr match {
      case Some(isa: InetSocketAddress) => isa.getHostString
      case _ => "-"
    }
    val identd = "-"
    val user = "-"
    val responseBytes = "-"
    val referer = reqHeaders.get("referer").getOrElse("-")
    val userAgent = reqHeaders.get("user-agent").getOrElse("-")
    var hostHeader = request.authority
    val reqResource = s"${request.method.toString.toUpperCase} ${request.path} HTTP/2"

    service(request).onSuccess { rsp =>
      val statusCode = rsp.status.code

      val requestEndTime = new TimeFormat("dd/MM/yyyy:HH:mm:ss Z").format(Time.now)
      log
        .debug(
          """%s %s %s %s [%s] "%s" %d %s "%s" "%s"""",
          hostHeader,
          remoteHost,
          identd,
          user,
          requestEndTime,
          reqResource,
          statusCode,
          responseBytes,
          referer,
          userAgent
        )
    }

  }
}
