package io.buoyant.router.http

import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.http.Request
import com.twitter.finagle.{Dtab, Path}
import com.twitter.util.{Future, Try}

object Identifier {
  def dstConcrete(req: Request, baseDtab: () => Dtab): Option[Future[(Dst, Request)]] = {
    req.headerMap.get("l5d-dst-concrete").flatMap { dst =>
      Try(Path.read(dst)).toOption
    }.map { dst =>
      Future.value((Dst.Path(dst, baseDtab()), req))
    }
  }
}
