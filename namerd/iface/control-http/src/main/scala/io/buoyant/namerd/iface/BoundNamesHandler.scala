package io.buoyant.namerd.iface

import com.twitter.finagle.http.{MediaType, Method, Request, Response}
import com.twitter.finagle.{Namer, Path, Service}
import com.twitter.io.Buf
import com.twitter.util.{Activity, Future}
import io.buoyant.namer.{EnumeratingNamer, RichActivity}

object BoundNamesUri {
  import io.buoyant.namerd.iface.HttpControlService._

  def unapply(req: Request): Option[Request] = {
    if (req.path == s"$apiPrefix/bound-names" && req.method == Method.Get)
      Some(req)
    else
      None
  }
}

class BoundNamesHandler(namers: Map[Path, Namer]) extends Service[Request, Response] {
  import HttpControlService._

  override def apply(req: Request): Future[Response] = req match {
    case BoundNamesUri(_) => handleGetBoundNames(req)
    case _ => NotFound
  }

  private[this] val enumeratingNamers = namers.values.collect {
    case namer: EnumeratingNamer => namer
  }.toSeq
  private[this] val boundNames =
    Activity.collect(enumeratingNamers.map(_.getAllNames))
      .map(_.toSet.flatten)

  private[this] def renderBoundNames(names: Set[Path]): Buf =
    Buf.Utf8(names.map(_.show).mkString("[\"", "\",\"", "\"]\n"))

  private[this] def handleGetBoundNames(req: Request): Future[Response] = {
    if (isStreaming(req)) {
      streamingResp(boundNames.values, Some(MediaType.Json))(renderBoundNames)
    } else {
      boundNames.toFuture.map { names =>
        val rsp = Response()
        rsp.contentType = MediaType.Json
        rsp.content = renderBoundNames(names)
        rsp
      }
    }
  }
}
