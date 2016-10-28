package io.buoyant.test

import com.twitter.logging._
import com.twitter.util.{Await, Duration, Future, Time, TimeoutException}

trait Logging { _: org.scalatest.FunSuite =>

  val log = Logger.get(getClass.getName)

  def setLogLevel(level: Level): Unit =
    Logger.configure(List(LoggerFactory(
      node = "",
      level = Some(level),
      handlers = List(ConsoleHandler())
    )))

}
