package io.buoyant.k8s

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.http.Request
import com.twitter.util.Time

class WatchState[G, W] {
  @JsonIgnore
  def recordApiCall(req: Request): Unit = {
    request = Some(s"${req.method} ${req.uri}")
    lastRequestAt = Some(Time.now.toString)
  }

  @JsonIgnore
  def recordResponse(init: G): Unit = {
    response = Some(init)
    lastResponseAt = Some(Time.now.toString)
  }

  @JsonIgnore
  def recordStreamStart(): Unit = {
    streaming = true
    lastStreamStartAt = Some(Time.now.toString)
  }

  @JsonIgnore
  def recordStreamData(w: W): Unit = {
    lastStreamData = Some(w)
    lastStreamDataAt = Some(Time.now.toString)
  }

  @JsonIgnore
  def recordStreamEnd(): Unit = {
    streaming = false
    lastStreamEndAt = Some(Time.now.toString)
  }

  var request: Option[String] = None
  var lastRequestAt: Option[String] = None
  var response: Option[G] = None
  var lastResponseAt: Option[String] = None
  var lastStreamStartAt: Option[String] = None
  var lastStreamData: Option[W] = None
  var lastStreamDataAt: Option[String] = None
  var lastStreamEndAt: Option[String] = None
  var streaming: Boolean = false
}
