package io.buoyant.admin.names

import com.twitter.finagle.Service
import com.twitter.finagle.http.{MediaType, Request, Response}
import com.twitter.util.{Activity, Future}
import io.buoyant.namer.EnumeratingNamer

class BoundNamesHandler(namers: Seq[EnumeratingNamer]) extends Service[Request, Response] {
  override def apply(req: Request): Future[Response] = {
    Activity.collect(namers.map(_.getAllNames)).map { names =>
      val json = names.toSet.flatten.map(_.show).mkString("[\"", "\",\"", "\"]")
      val resp = req.response
      resp.contentString = json
      resp.contentType = MediaType.Json
      resp
    }.values.toFuture.flatMap(Future.const)
  }
}
