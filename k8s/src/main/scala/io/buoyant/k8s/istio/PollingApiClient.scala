package io.buoyant.k8s.istio

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.util.DefaultTimer
import com.twitter.logging.Logger
import com.twitter.util._
import io.buoyant.config.Parser

/**
 * A base class for Api clients that watch a JSON HTTP API by polling it at a regular interval.
 */
abstract class PollingApiClient(client: Service[Request, Response]) {

  protected val log = Logger()
  protected val mapper = Parser.jsonObjectMapper(Nil)
  mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
  registerModules(mapper)

  def registerModules(mapper: ObjectMapper): Unit = {}

  protected def get[T: Manifest](url: String): Future[T] = {
    val req = Request(url)
    client(req).map { rsp =>
      mapper.readValue[T](rsp.contentString)
    }
  }

  protected def watch[T: Manifest](
    url: String,
    pollInterval: Duration
  )(implicit timer: Timer = DefaultTimer): Activity[T] = {
    val state = Var.async[Activity.State[T]](Activity.Pending) { update =>

      def doUpdate() = get[T](url).respond {
        case Return(rsp) => update.update(Activity.Ok(rsp))
        case Throw(e) =>
          log.error(e, s"failed to watch resource: $url")
          update.update(Activity.Failed(e))
      }

      val _ = doUpdate()

      timer.schedule(pollInterval) {
        val _ = doUpdate()
      }
    }
    Activity(state)
  }
}
