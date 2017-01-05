package io.buoyant.telemetry.requestLog

import com.twitter.finagle.http.{MediaType, Request, Response, Status}
import com.twitter.util.Future
import io.buoyant.admin.Admin.Handler
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.ISO_8859_1

class RequestLogAdminHandler(requestLog: RequestLogTracer) extends Handler {

  override def apply(req: Request): Future[Response] = {

    val tbody = requestLog.getRequests.map { requestMeta =>

      val encodedLogicalName = URLEncoder.encode(requestMeta.logicalName, ISO_8859_1.toString)
      val encodedConcreteName = URLEncoder.encode(requestMeta.concreteName, ISO_8859_1.toString)

      val logicalNameUrl = s"/delegator?router=${requestMeta.router}#$encodedLogicalName"
      val concreteNameUrl = s"/delegator?router=${requestMeta.router}#$encodedConcreteName"

      val row = Seq(
        requestMeta.timestamp.toString,
        requestMeta.source,
        requestMeta.server,
        requestMeta.router,
        s"<a href=$logicalNameUrl>${requestMeta.logicalName}</a>",
        s"<a href=$concreteNameUrl>${requestMeta.concreteName}</a>",
        requestMeta.destination
      ).map(cell => s"<td>$cell</td>").mkString
      s"<tr>$row</tr>"
    }.mkString

    val header = Seq(
      "Timestamp", "Source", "Server", "Router", "Logical Name", "Concrete Name", "Destination"
    ).map(cell => s"<th>$cell</th>").mkString

    req.response.contentString =
      s"""
       |<div class="container main">
       |<h1 class="title">Recent Requests</h1>
       |<div class="content">
       |<table class="table table-bordered">
       |<thead><tr>$header</tr></thead>
       |<tbody>$tbody</tbody>
       |</table>
       |</div>
       |</div>
     """.stripMargin
    req.response.contentType = MediaType.Html
    Future.value(req.response)
  }
}
