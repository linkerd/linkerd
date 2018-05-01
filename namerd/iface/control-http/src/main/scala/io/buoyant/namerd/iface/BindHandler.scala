package io.buoyant.namerd.iface

import com.twitter.finagle._
import com.twitter.finagle.http.{Request, Response}
import com.twitter.io.Buf
import com.twitter.util.Future
import io.buoyant.admin.DelegationJsonCodec
import io.buoyant.admin.names.DelegateApiHandler.JsonDelegateTree
import io.buoyant.namer.{DelegateTree, RichActivity}

object BindUri extends NsPathUri {
  import HttpControlService._

  val prefix = s"$apiPrefix/bind"
}

class BindHandler(cache: NameInterpreterCache) extends Service[Request, Response] {
  import HttpControlService._

  override def apply(req: Request): Future[Response] = req match {
    case BindUri(Some(ns), path) => handleGetBind(ns, path, req)
    case _ => NotFound
  }

  private[this] def handleGetBind(ns: String, path: Path, req: Request): Future[Response] = {
    val extraDtab = req.params.get("dtab")
    if (isStreaming(req)) {
      streamingRespF(cache.getBind(ns, path, extraDtab).values)(BindHandler.renderNameTree)
    } else {
      val nameTree = cache.getBind(ns, path, extraDtab).toFuture
      nameTree.flatMap(BindHandler.renderNameTree).map { buf =>
        val rsp = Response()
        rsp.content = buf
        rsp
      }
    }
  }
}

object BindHandler {
  def renderNameTree(tree: NameTree[Name.Bound]): Future[Buf] =
    JsonDelegateTree.mk(
      DelegateTree.fromNameTree(tree, path = null)
    ).map(DelegationJsonCodec.writeBuf).map(_.concat(Buf.Utf8("\n")))
}
