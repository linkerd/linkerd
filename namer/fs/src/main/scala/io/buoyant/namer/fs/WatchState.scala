package io.buoyant.namer.fs

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.util.Time
import java.nio.file.{Path, WatchEvent}

class WatchState {
  @JsonIgnore
  def recordEvent(ev: WatchEvent[_]): Unit = synchronized {
    lastEventKind = Some(ev.kind().name())
    ev.context() match {
      case p: Path => lastEventName = Some(p.toString)
      case _ =>
    }
    lastEventAt = Some(Time.now.toString)
  }

  // These fields exist to be serialized.
  protected var lastEventKind: Option[String] = None
  protected var lastEventName: Option[String] = None
  protected var lastEventAt: Option[String] = None
}
