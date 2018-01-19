package io.buoyant.linkerd.protocol.h2

import com.twitter.finagle.{Service, ServiceFactory, SimpleFilter, Stack, Stackable}
import com.twitter.finagle.buoyant.h2.{Request, Response}
import com.twitter.logging._
import com.twitter.util.{Time, TimeFormat}

case class H2AccessLogger(log: Logger) extends SimpleFilter[Request, Response] {

  def apply(req: Request, svc: Service[Request, Response]) = {
    val reqHeaders = req.headers
    val remoteHost = "-"
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

object H2AccessLogger {

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
      val role = Stack.Role("H2AccessLogger")
      val description = "Log h2 requests/response summaries to a file"
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
            new H2AccessLogger(logger()).andThen(factory)
        }
    }

}
