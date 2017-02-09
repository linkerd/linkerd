package io.buoyant.admin

import com.twitter.app.{App => TApp}
import com.twitter.logging.Logging
import com.twitter.server._

trait App extends TApp
  with Linters
  with Logging
  with EventSink
  with TimeZoneLogFormat
  with Hooks
  with Lifecycle
  with Stats
