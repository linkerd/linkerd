package io.buoyant.namer.consul

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.http
import com.twitter.util.{Return, Throw, Time, Try, Future}
import io.buoyant.consul.v1

/**
 * PollState holds metadata about the calls on consul api.
 * This class is intended to be serialized.
 *
 */
class PollState[Req, Rep] {

  @JsonIgnore
  def recordApiCall(req: Req): Unit = synchronized {
    request = Some(req)
    lastRequestAt = Some(Time.now.toString)
  }

  @JsonIgnore
  def recordResponse(rep: Try[Rep]): Unit = synchronized {
    lastResponseAt = Some(Time.now.toString)
    rep match {
      case Return(r) =>
        response = Some(r)
        error = None
      case Throw(e) =>
        error = Some(e)
        response = None
    }
  }

  // These fields exist to be serialized.
  protected var request: Option[Req] = None
  protected var lastRequestAt: Option[String] = None
  protected var response: Option[Rep] = None
  protected var lastResponseAt: Option[String] = None
  protected var error: Option[Throwable] = None
}

object InstrumentedApiCall {

  def execute[Rep](call: v1.ApiCall[Rep], watch: PollState[http.Request, Rep]): Future[Rep] = {
    watch.recordApiCall(call.req)
    val f = call()
    f.respond(watch.recordResponse)
    f
  }

}