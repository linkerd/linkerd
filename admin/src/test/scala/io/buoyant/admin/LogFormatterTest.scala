package io.buoyant.admin

import java.util.TimeZone
import java.util.logging.{Level, LogRecord}

import com.twitter.util.TimeFormat
import io.buoyant.test.FunSuite

class LogFormatterTest extends FunSuite {

  val record = new LogRecord(Level.INFO, "Logging useful info")
  record.setMillis(1519468460239L)
  record.setThreadID(10)

  test("uses UTC timezone") {
    val utcFormatter = new LogFormatter(
      new TimeFormat(" MMdd HH:mm:ss.SSS z", TimeZone.getTimeZone("UTC"))
    )
    assert(
      utcFormatter
        .format(record) == "I 0224 10:34:20.239 UTC THREAD10: Logging useful info\n"
    )
  }

  test("uses non UTC timezone") {
    val gmtFormatter = new LogFormatter(
      new TimeFormat(" MMdd HH:mm:ss.SSS z", TimeZone.getTimeZone("GMT+8"))
    )
    assert(
      gmtFormatter
        .format(record) == "I 0224 18:34:20.239 GMT+08:00 THREAD10: Logging useful info\n"
    )
  }
}
