package io.buoyant.admin

import java.util.TimeZone
import java.util.logging.{Level, LogRecord}

import com.twitter.util.TimeFormat
import org.scalatest.WordSpec

class LogFormatterTest extends WordSpec {

  val record = new LogRecord(Level.INFO, "Logging useful info")
  record.setMillis(1519468460239L)
  record.setThreadID(1)

  "LogFormatter" should {

    "use UTC timezone" in {
      val utcFormatter = new LogFormatter(
        new TimeFormat(" MMdd HH:mm:ss.SSS z", TimeZone.getTimeZone("UTC"))
      )
      assert(utcFormatter
        .format(record) == "I 0224 10:34:20.239 UTC THREAD1: Logging useful info\n")
    }

    "use non UTC timezone" in {
      val utcFormatter = new LogFormatter(
        new TimeFormat(" MMdd HH:mm:ss.SSS z", TimeZone.getTimeZone("GMT+8"))
      )
      assert(utcFormatter
        .format(record) == "I 0224 18:34:20.239 GMT+08:00 THREAD1: Logging useful info\n")
    }

  }
}
