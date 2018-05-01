package io.buoyant.namerd.iface

import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.{Addr, Path, Service}
import com.twitter.io.Buf
import com.twitter.util.Future
import io.buoyant.admin.DelegationJsonCodec
import io.buoyant.admin.names.DelegateApiHandler.{Addr => JsonAddr}
import io.buoyant.namer.{Paths, RichActivity}

object AddrUri extends NsPathUri {
  import HttpControlService._

  val prefix = s"$apiPrefix/addr"
}

class AddrHandler(cache: NameInterpreterCache) extends Service[Request, Response] {
  import HttpControlService._

  override def apply(req: Request): Future[Response] = req match {
    case AddrUri(Some(ns), fullPath) =>
      val path = Paths.stripTransformerPrefix(fullPath)
      get(ns, path, req)
    case _ => NotFound
  }

  private[this] def get(ns: String, path: Path, req: Request): Future[Response] = {
    if (isStreaming(req)) {
      streamingResp(cache.getAddr(ns, path).values)(AddrHandler.renderAddr)
    } else {
      cache.getAddr(ns, path).toFuture.map { addr =>
        val rsp = Response()
        rsp.content = AddrHandler.renderAddr(addr)
        rsp
      }
    }
  }
}

object AddrHandler {
  def renderAddr(addr: Addr): Buf =
    DelegationJsonCodec.writeBuf(JsonAddr.mk(addr)).concat(Buf.Utf8("\n"))
}
