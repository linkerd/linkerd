package io.buoyant.consul

import com.twitter.finagle.{Failure, Http}
import com.twitter.util.Monitor
import io.buoyant.config.types.Port

object utils {

  object RichConsulClient {
    val monitor = Monitor.mk {
      case e: Failure if e.isFlagged(Failure.Interrupted) => true
    }
  }

  implicit class RichConsulClient(val client: Http.Client) extends AnyVal {
    import RichConsulClient._

    def failFast(enabled: Option[Boolean]): Http.Client =
      enabled match {
        case Some(true) => client
        case _ => client
          .withSessionQualifier.noFailFast
          .withSessionQualifier.noFailureAccrual
      }

    def interceptInterrupts: Http.Client =
      client.withMonitor(monitor)

    def ensureHost(host: Option[String], port: Option[Port]): Http.Client = client.filtered(
      new SetHostFilter(
        host.getOrElse("localhost"),
        port.map(_.port).getOrElse(8500)
      )
    )

    def setAuthToken(token: Option[String]): Http.Client = token match {
      case Some(t) => client.filtered(new SetAuthTokenFilter(t))
      case _ => client
    }
  }
}
