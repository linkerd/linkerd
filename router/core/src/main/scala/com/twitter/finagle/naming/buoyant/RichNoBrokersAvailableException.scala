package com.twitter.finagle.naming.buoyant

import com.twitter.finagle._
import com.twitter.finagle.buoyant.Dst
import com.twitter.util.Future

class RichNoBrokersAvailableException(
  path: Dst.Path,
  dtab: Option[Dtab]
) extends RequestException {

  private[this] def formatDtab(d: Dtab): String =
    d.map { dentry =>
      s"  ${dentry.prefix.show} => ${dentry.dst.show}"
    }.mkString("\n")

  override def exceptionMessage(): String =
    s"""Unable to route request!

service name: ${path.path.show}
dtab:
${formatDtab(dtab.getOrElse(Dtab.empty))}
base dtab:
${formatDtab(path.baseDtab)}
override dtab:
${formatDtab(path.localDtab)}
""".stripMargin
}

object RichNoBrokersAvailableException {

  def apply(path: Dst.Path): Future[Nothing] =
    Future.exception(new RichNoBrokersAvailableException(path, None))
}
