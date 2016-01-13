package io.buoyant.router.http

import com.twitter.finagle.{Service, ServiceFactory, SimpleFilter, Stack, Stackable}
import com.twitter.finagle.http.{Request, Response}
import com.twitter.logging._
import com.twitter.util.{Time, TimeFormat}
import java.lang.{StringBuilder => JStringBuilder}

case class AccessLogger(log: Logger) extends SimpleFilter[Request, Response] {

  def apply(req: Request, svc: Service[Request, Response]) = {
    val reqHeaders = req.headerMap
    val remoteHost = req.remoteHost
    val identd = "-"
    val user = "-"
    val referer = reqHeaders.getOrElse("Referer", "-")
    val userAgent = reqHeaders.getOrElse("User-Agent", "-")

    def buildLogLine(
      remoteHost: String,
      identd: String,
      user: String,
      requestEndTime: String,
      method: String,
      uri: String,
      version: String,
      statusCode: String,
      responseBytes: String,
      referer: String,
      userAgent: String
    ): String =
      {
        val logline = new JStringBuilder(2048)
        logline.append(remoteHost)
        logline.append(" ")
        logline.append(identd)
        logline.append(" ")
        logline.append(user)
        logline.append(" [")
        logline.append(requestEndTime)
        logline.append("] \"")
        logline.append(method)
        logline.append(" ")
        logline.append(uri)
        logline.append(" ")
        logline.append(version)
        logline.append("\" ")
        logline.append(statusCode)
        logline.append(" ")
        logline.append(responseBytes)
        logline.append(" \"")
        logline.append(referer)
        logline.append("\" \"")
        logline.append(userAgent)
        logline.append("\"")
        return logline.toString()
      }
    svc(req).onSuccess { rsp =>
      val statusCode = rsp.statusCode
      val responseBytes = rsp.contentLength.map(_.toString).getOrElse("-")
      val requestEndTime = new TimeFormat("dd/MM/yyyy:HH:mm:ss Z").format(Time.now)
      log.info(buildLogLine(
        remoteHost,
        identd,
        user,
        requestEndTime,
        req.method.toString.toUpperCase,
        req.uri,
        req.version.toString(),
        statusCode.toString(),
        responseBytes,
        referer,
        userAgent
      ))
    }
  }
}

object AccessLogger {

  object param {
    case class File(path: String)
    implicit object File extends Stack.Param[File] {
      val default = File("")
    }

    case class RollPolicy(policy: Policy)
    implicit object RollPolicy extends Stack.Param[RollPolicy] {
      val default = RollPolicy(Policy.Never)
    }

    case class Append(append: Boolean)
    implicit object Append extends Stack.Param[Append] {
      val default = Append(true)
    }

    case class RotateCount(count: Int)
    implicit object RotateCount extends Stack.Param[RotateCount] {
      val default = RotateCount(-1)
    }
  }

  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module4[param.File, param.RollPolicy, param.Append, param.RotateCount, ServiceFactory[Request, Response]] {
      val role = Stack.Role("HttpAccessLogger")
      val description = "Log Http requests/response summaries to a file"
      def make(
        file: param.File,
        roll: param.RollPolicy,
        append: param.Append,
        rotate: param.RotateCount,
        factory: ServiceFactory[Request, Response]
      ): ServiceFactory[Request, Response] =
        file match {
          case param.File("") => factory
          case param.File(path) =>
            val logger = LoggerFactory(
              node = "access",
              level = Some(Level.INFO),
              handlers = List(FileHandler(
                path, roll.policy, append.append, rotate.count,
                // avoid the default prefix
                formatter = new com.twitter.logging.Formatter(prefix = ""),
                level = Some(Level.INFO)
              )),
              useParents = false
            )
            new AccessLogger(logger()).andThen(factory)
        }
    }

}
