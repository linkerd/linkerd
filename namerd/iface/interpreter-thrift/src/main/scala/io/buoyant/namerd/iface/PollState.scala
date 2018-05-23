package io.buoyant.namerd.iface

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.util.{Return, Throw, Time, Try}

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
