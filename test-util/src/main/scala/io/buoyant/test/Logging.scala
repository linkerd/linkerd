package io.buoyant.test

import com.twitter.logging._
import com.twitter.util.{Await, Duration, Future, Time, TimeoutException}
import org.scalatest.BeforeAndAfter

trait Logging extends BeforeAndAfter { _: org.scalatest.FunSuite =>

  var logLevel: Level = Level.OFF

  before {
    Logger.configure(List(LoggerFactory(
      node = "",
      level = Some(logLevel),
      handlers = List(ConsoleHandler())
    )))
  }

  after {
    Logger.reset()
  }
}
