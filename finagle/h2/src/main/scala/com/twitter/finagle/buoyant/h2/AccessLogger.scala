package com.twitter.finagle.buoyant.h2

import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.finagle.context.RemoteInfo
import com.twitter.logging._
import com.twitter.util._
import java.net.InetSocketAddress

object AccessLogger {
  def mk(
    logFilePath: Option[String],
    logRollPolicy: Option[String],
    logAppend: Option[Boolean],
    logRotateCount: Option[Int]
  ): Option[AccessLogger] = {
    val policy = logRollPolicy match {
      case None => Policy.Never
      case Some(policyName) =>
        Try(Policy.parse(policyName)) match {
          // Default to Never roll policy if we can't parse one from the provided string
          case Throw(_) => Policy.Never
          case Return(r) => r
        }
    }
    val append = logAppend.getOrElse(true)
    val logRotate = logRotateCount.getOrElse(-1)

    val filter = logFilePath match {
      case Some(path) if path != "" =>
        val logger = LoggerFactory(
          node = "access_io.l5d.mesh",
          level = Some(Level.INFO),
          handlers = List(
            FileHandler(
              path,
              policy,
              append,
              logRotate,
              new Formatter(prefix = ""),
              Some(Level.INFO)
            )
          ),
          useParents = false
        )
        Some(AccessLogger(logger()))
      case _ => None
    }
    filter
  }
}

case class AccessLogger(log: Logger) extends SimpleFilter[Request, Response] {

  def apply(req: Request, svc: Service[Request, Response]) = {
    val reqHeaders = req.headers
    val remoteHost = RemoteInfo.Upstream.addr match {
      case Some(isa: InetSocketAddress) => isa.getHostString
      case _ => "-"
    }
    val identd = "-"
    val user = "-"
    val referer = reqHeaders.get("referer").getOrElse("-")
    val userAgent = reqHeaders.get("user-agent").getOrElse("-")
    var hostHeader = req.authority
    val reqResource = s"${req.method.toString.toUpperCase} ${req.path} HTTP/2"

    svc(req).onSuccess { rsp =>
      val statusCode = rsp.status.code
      val responseBytes = "-"
      val requestEndTime = new TimeFormat("dd/MM/yyyy:HH:mm:ss Z").format(Time.now)
      log.info("""%s %s %s %s [%s] "%s" %d %s "%s" "%s"""", hostHeader, remoteHost, identd, user, requestEndTime,
        reqResource, statusCode, responseBytes, referer, userAgent)
    }
  }
}
