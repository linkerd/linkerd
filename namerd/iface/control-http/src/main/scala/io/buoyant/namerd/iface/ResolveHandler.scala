package io.buoyant.namerd.iface

import com.twitter.finagle._
import com.twitter.finagle.http.{MediaType, Request, Response}
import com.twitter.finagle.util.{Drv, Rng}
import com.twitter.util.{Activity, Future}
import io.buoyant.namer.RichActivity

object ResolveUri extends NsPathUri {
  import HttpControlService._

  val prefix = s"$apiPrefix/resolve"
}

class ResolveHandler(cache: NameInterpreterCache) extends Service[Request, Response] {
  import HttpControlService._

  override def apply(req: Request): Future[Response] = req match {
    case ResolveUri(Some(ns), path) =>
      get(ns, path, req)
    case _ => NotFound
  }

  private[this] def flattenTree(tree: NameTree[Name.Bound], rng: Rng = Rng.threadLocal): Option[Path] = {
    tree match {
      case NameTree.Neg | NameTree.Fail | NameTree.Empty | NameTree.Alt() => None
      case NameTree.Leaf(bound) => bound.id match {
        case p: Path => Some(p)
        case _ => None
      }
      case NameTree.Union(weightedTrees@_*) =>
        val (weights, trees) = weightedTrees.unzip { case NameTree.Weighted(w, t) => (w, t) }
        val drv = Drv.fromWeights(weights)
        val randomTree = trees(drv(rng))
        flattenTree(randomTree, rng)
      case NameTree.Alt(alts@_*) =>
        flattenTree(alts.head) match {
          case p: Some[Path] => p
          case None => flattenTree(NameTree.Alt(alts.tail: _*), rng)
        }
    }
  }

  private[this] def get(ns: String, path: Path, req: Request): Future[Response] = {
    val extraDtab = req.params.get("dtab")

    val activity = cache.getBind(ns, path, extraDtab).flatMap { tree =>
      flattenTree(tree) match {
        case Some(p) => cache.getAddr(ns, p)
        case None => Activity.value(Addr.Neg)
      }
    }

    if (isStreaming(req)) {
      streamingResp(activity.values, Some(MediaType.Json))(AddrHandler.renderAddr)
    } else {
      activity.toFuture.map { addr =>
        // maybe we should return 404 on Addr.Neg and 302 with Retry-After header on Addr.Pending?..
        val rsp = Response()
        rsp.content = AddrHandler.renderAddr(addr)
        rsp.contentType = MediaType.Json
        rsp
      }
    }
  }
}
