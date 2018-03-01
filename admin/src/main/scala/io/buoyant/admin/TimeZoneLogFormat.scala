package io.buoyant.admin

import com.twitter.app.{App => TApp}
import com.twitter.finagle.tracing.Trace
import com.twitter.logging.{Level => TwLevel}
import com.twitter.util.{Time, TimeFormat}
import java.util.logging.{Formatter, Level, LogRecord, Logger}
import java.io.{PrintWriter, StringWriter}
import java.util.TimeZone

import scala.reflect.NameTransformer

/**
 * A fork of twitter-server's LogFormat.scala which adds a TimeZone.
 * https://raw.githubusercontent.com/twitter/twitter-server/7e52ba10aacbcb269da9fcb3c1a87289525848c2/src/main/scala/com/twitter/server/LogFormat.scala
 */

trait TimeZoneLogFormat { app: TApp =>
  private val defaultFormat = new TimeFormat(" MMdd HH:mm:ss.SSS z", TimeZone.getDefault)
  premain {
    for (h <- Logger.getLogger("").getHandlers)
      h.setFormatter(new LogFormatter(defaultFormat))
  }
}

/**
 * Implements "glog" style log formatting. Adds a timezone after the timestamp.
 */
private class LogFormatter(timeFormat: TimeFormat) extends Formatter {
  private val levels = Map[Level, Char](
    Level.FINEST -> 'D',
    Level.FINER -> 'D',
    Level.FINE -> 'D',
    TwLevel.TRACE -> 'D',
    TwLevel.DEBUG -> 'D',
    Level.CONFIG -> 'I',
    Level.INFO -> 'I',
    TwLevel.INFO -> 'I',
    Level.WARNING -> 'W',
    TwLevel.WARNING -> 'W',
    Level.SEVERE -> 'E',
    TwLevel.ERROR -> 'E',
    TwLevel.CRITICAL -> 'E',
    TwLevel.FATAL -> 'E'
  )

  // Make some effort to demangle scala names.
  private def prettyClass(name: String): String = {
    var s = NameTransformer.decode(name)
    val dolladolla = s.indexOf("$$")
    if (dolladolla > 0) {
      s = s.substring(0, dolladolla)
      s += "~"
    }

    s
  }

  override def format(r: LogRecord): String = {
    val msg = formatMessage(r)

    val str = new StringBuilder(msg.length + 30 + 150)
      .append(levels.getOrElse(r.getLevel, 'U'))
      .append(timeFormat.format(Time.fromMilliseconds(r.getMillis)))
      .append(" THREAD")
      .append(r.getThreadID)

    for (id <- Trace.idOption) {
      str.append(" TraceId:")
      str.append(id.traceId)
    }

    if (r.getSourceClassName != null) {
      str.append(' ').append(prettyClass(r.getSourceClassName))
      if (r.getSourceMethodName != null)
        str.append('.').append(r.getSourceMethodName)
    }

    str.append(": ")
    str.append(msg)

    if (r.getThrown != null) {
      val w = new StringWriter
      r.getThrown.printStackTrace(new PrintWriter(w))
      str.append('\n').append(w.toString)
    }

    str.append('\n')
    str.toString
  }
}
