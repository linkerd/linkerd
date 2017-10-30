package io.buoyant.namerd.storage

import com.twitter.finagle.{Dtab, Http}
import com.twitter.io.Buf
import com.twitter.util.Future

class CrdK8sDTabStore(client: Http.Client, dst: String, namespace: String) extends K8sDtabStore(client, dst, namespace) {
  override def put(ns: String, dtab: Dtab): Future[Unit] = {
    api.dtabs.named(ns).get().flatMap {
      case Some(v) =>
        val resourceVersion = for {
          m <- v.metadata
          r <- m.resourceVersion
        } yield r

        resourceVersion match {
          case Some(r) => update(ns, dtab, Buf.Utf8(r))
          case _ => Future.exception(new Exception())
        }
      case _ => Future.Unit
    }
    Future.Unit
  }
}
