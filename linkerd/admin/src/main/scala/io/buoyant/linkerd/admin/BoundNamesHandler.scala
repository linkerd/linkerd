package io.buoyant.linkerd.admin

import com.twitter.finagle.Service
import com.twitter.finagle.http.{MediaType, Request, Response}
import com.twitter.util.Future
import io.buoyant.namer.EnumeratingNamer

class BoundNamesHandler(namers: Seq[EnumeratingNamer]) extends Service[Request, Response] {
  override def apply(req: Request): Future[Response] = {
    Future.collect(namers.map(_.getAllNames)).flatMap { names =>
      val json = names.toSet.flatten.map(_.show).mkString("[\"", "\",\"", "\"]")
      AdminHandler.mkResponse(json, MediaType.Json)
    }
  }
}
