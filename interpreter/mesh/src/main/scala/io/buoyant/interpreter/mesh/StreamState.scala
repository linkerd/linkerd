package io.buoyant.interpreter.mesh

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.util.{Return, Throw, Time, Try}

/**
 * StreamState holds metadata about the state of a gRPC stream.  This class is intended to be
 * serialized.
 *
 * @tparam Req The request message type.
 * @tparam Rep The response stream object type.
 */
class StreamState[Req, Rep] {
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
        streaming = true
        response = Some(r)
        error = None
      case Throw(e) =>
        error = Some(e)
        response = None
    }
  }

  @JsonIgnore
  def recordStreamEnd(): Unit = synchronized {
    streaming = false
    lastStreamEndAt = Some(Time.now.toString)
  }

  // These fields exist to be serialized.
  protected var request: Option[Req] = None
  protected var lastRequestAt: Option[String] = None
  protected var response: Option[Rep] = None
  protected var error: Option[Throwable] = None
  protected var lastResponseAt: Option[String] = None
  protected var lastStreamEndAt: Option[String] = None
  protected var streaming: Boolean = false
}
