package io.buoyant.marathon.v2

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.finagle.http.Request
import com.twitter.util.Time
import io.buoyant.marathon.v2.Api.AppRsp

class WatchState {

  @JsonIgnore
  def recordApiCall(req: Request): Unit = synchronized {
    request = Some(s"${req.method} ${req.uri}")
    lastRequestAt = Some(Time.now.toString)
  }

  @JsonIgnore
  def recordResponse(apps: AppRsp): Unit = synchronized {
    lastResponseAt = Some(Time.now.toString)
    response = Some(apps)
  }

  // These fields exist to be serialized.
  protected var request: Option[String] = None
  protected var lastRequestAt: Option[String] = None
  protected var response: Option[AppRsp] = None
  protected var error: Option[Throwable] = None
  protected var lastResponseAt: Option[String] = None
  protected var lastStreamEndAt: Option[String] = None
}
