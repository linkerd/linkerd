package io.buoyant.admin.names

import com.twitter.finagle.Service
import com.twitter.finagle.http.{MediaType, Request, Response}
import com.twitter.util.{Activity, Future}
import io.buoyant.namer.EnumeratingNamer

class BoundNamesHandler(namers: Seq[EnumeratingNamer]) extends Service[Request, Response] {
  override def apply(req: Request): Future[Response] = {
    Activity.collect(namers.map(_.getAllNames))
      .values
      .toFuture
      .flatMap(Future.const)
      .map { names =>
        val json = names.toSet.flatten.map(_.show).mkString("[\"", "\",\"", "\"]")
        val rsp = req.response
        rsp.contentString = json
        rsp.contentType = MediaType.Json
        rsp
      }
  }
}
