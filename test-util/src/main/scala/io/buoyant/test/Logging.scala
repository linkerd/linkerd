package io.buoyant.test

import com.twitter.logging._
import com.twitter.util.{Await, Duration, Future, Time, TimeoutException}
import org.scalatest.BeforeAndAfter

trait Logging extends BeforeAndAfter { _: org.scalatest.FunSuite =>

  val log = Logger.get(getClass.getName)

  def logLevel: Level = Level.OFF

  before {
    Logger.configure(List(LoggerFactory(
      node = "",
      level = Some(logLevel),
      handlers = List(ConsoleHandler())
    )))
  }

  after {
    Logger.configure(List(LoggerFactory(
      node = "",
      level = Some(Level.OFF),
      handlers = List(ConsoleHandler())
    )))
  }
}
