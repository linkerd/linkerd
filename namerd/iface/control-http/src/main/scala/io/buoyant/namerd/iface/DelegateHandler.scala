package io.buoyant.namerd.iface

import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.{Namer, Dtab, Path, Service}
import com.twitter.finagle.http.{Status, Request, Response}
import com.twitter.util.Future
import io.buoyant.admin.names.DelegateApiHandler
import io.buoyant.namer.Delegator
import io.buoyant.namerd.Ns

object DelegateUri extends NsPathUri {
  import HttpControlService._

  val prefix = s"$apiPrefix/delegate"
}

class DelegateHandler(delegate: Ns => NameInterpreter, namers: Map[Path, Namer]) extends Service[Request, Response] {
  import HttpControlService._

  private[this] val delegateApiHander = new DelegateApiHandler(delegate, namers.toSeq)

  override def apply(req: Request): Future[Response] = req match {
    case DelegateUri(Some(ns), path) =>
      get(ns, path, req.params.get("dtab"))
    case DelegateUri(None, path) =>
      delegateApiHander(req)
    case _ => NotFound
  }

  private[this] def get(ns: String, path: Path, extraDtab: Option[String]): Future[Response] = {
    delegate(ns) match {
      case delegator: Delegator =>
        val fullDtab = extraDtab match {
          case Some(str) => Dtab.read(str)
          case None => Dtab.empty
        }
        DelegateApiHandler.getDelegateRsp(fullDtab.show, path.show, delegator)
      case _ =>
        val rsp = Response(Status.NotImplemented)
        rsp.contentString = s"Name Interpreter for $ns cannot show delegations"
        Future.value(rsp)
    }
  }
}
