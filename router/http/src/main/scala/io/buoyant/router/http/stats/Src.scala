package io.buoyant.router.http.stats

import com.twitter.finagle.http.Request

final case class Src(name: String)
object Src {
  type SrcIdentifier = Request => Option[Src]

  val SrcServiceHeader = "l5d-src-service"
  val HeaderSrcIdentifier = (req: Request) => {
    req.headerMap.get(SrcServiceHeader).map(Src(_))
  }
}
