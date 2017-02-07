package io.buoyant.admin

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.finagle.Service
import com.twitter.finagle.http._
import com.twitter.logging.{Level, Logger}
import com.twitter.util.Future
import java.util.{logging => javalog}
import scala.annotation.tailrec

object LoggingHandler {
  implicit val loggerOrder: Ordering[Logger] = Ordering.by(_.name)

  val mapper = new ObjectMapper with ScalaObjectMapper
  mapper.registerModule(DefaultScalaModule)
  mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
  mapper.setSerializationInclusion(JsonInclude.Include.NON_ABSENT)

  def getLevel(logger: Logger): javalog.Level = {
    @tailrec
    def go(l: javalog.Logger): javalog.Level = {
      if (l.getLevel != null) l.getLevel
      else if (l.getParent != null) go(l.getParent)
      else Level.OFF // root has no level set
    }
    go(javalog.Logger.getLogger(logger.name))
  }

  def getAllLoggers(): Seq[LoggerObject] = {
    Logger.iterator.toSeq.sorted(LoggingHandler.loggerOrder).map { logger =>
      val loggerName = if (logger.name == "") "root" else logger.name
      val level = LoggingHandler.getLevel(logger).getName
      LoggerObject(loggerName, level)
    }
  }

}

case class LoggerObject(logger: String, level: String)

class LoggingHandler(
  view: HtmlView
) extends Service[Request, Response] {
  import LoggingHandler._

  def apply(request: Request): Future[Response] = {
    val loggers = getAllLoggers()
    view.mkResponse(view.html(
      content = s"""
      <table class="table table-striped table-condensed">
        <thead>
          <tr>
            <th>com.twitter.logging.Logger</th>
            <th class="pull-right">com.twitter.logging.Level</th>
          </tr>
        </thead>
        </table>
        """,
      navHighlight = "logging",
      tailContent = s"""<script id="logger-data" type="application/json">${mapper.writeValueAsString(loggers)}</script>"""
    ))

  }
}

class LoggingApiHandler() extends Service[Request, Response] {
  import LoggingHandler._
  def apply(req: Request): Future[Response] = {
    req.method match {
      case Method.Post =>
        (req.params.get("level"), req.params.get("logger")) match {
          case (Some(level), Some(name)) =>
            val nameOrRoot = if (name == "root") "" else name
            val updated = for {
              level <- Logger.levelNames.get(level.toUpperCase)
              logger <- Logger.iterator.find(_.name == nameOrRoot)
            } yield {
              logger.setLevel(level)
            }
            Future.value(Response(Status.Ok))
          case _ => Future.value(Response(Status.BadRequest))
        }
      case Method.Get =>
        val response = Response()
        response.contentType = MediaType.Json
        response.contentString = mapper.writeValueAsString(getAllLoggers())
        Future.value(response)
      case _ => Future.value(Response(Status.MethodNotAllowed))

    }
  }
}
