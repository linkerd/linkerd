package io.buoyant.k8s

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.http.Request
import com.twitter.util.Time

/**
 * WatchState holds metadata about the state of a Kubernetes watch.  This class is intended to be
 * serialized.
 * @tparam G The watched resource type.
 * @tparam W The watched resource watch event type.
 */
class WatchState[G, W] {
  @JsonIgnore
  def recordApiCall(req: Request): Unit = synchronized {
    request = Some(s"${req.method} ${req.uri}")
    lastRequestAt = Some(Time.now.toString)
  }

  @JsonIgnore
  def recordResponse(init: G): Unit = synchronized {
    response = Some(init)
    lastResponseAt = Some(Time.now.toString)
  }

  @JsonIgnore
  def recordStreamStart(): Unit = synchronized {
    streaming = true
    lastStreamStartAt = Some(Time.now.toString)
  }

  @JsonIgnore
  def recordStreamData(w: W): Unit = synchronized {
    lastStreamData = Some(w)
    lastStreamDataAt = Some(Time.now.toString)
  }

  @JsonIgnore
  def recordStreamEnd(): Unit = synchronized {
    streaming = false
    lastStreamEndAt = Some(Time.now.toString)
  }

  // These fields exist to be serialized.
  protected var request: Option[String] = None
  protected var lastRequestAt: Option[String] = None
  protected var response: Option[G] = None
  protected var lastResponseAt: Option[String] = None
  protected var lastStreamStartAt: Option[String] = None
  protected var lastStreamData: Option[W] = None
  protected var lastStreamDataAt: Option[String] = None
  protected var lastStreamEndAt: Option[String] = None
  protected var streaming: Boolean = false
}
