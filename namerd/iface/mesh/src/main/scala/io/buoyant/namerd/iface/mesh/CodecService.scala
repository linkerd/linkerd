package io.buoyant.namerd
package iface.mesh

import com.twitter.finagle.{Addr, Dentry, Dtab, Name, Namer, NameTree, Path}
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.util.{Activity, Future, Return, Throw, Try, Var}
import io.buoyant.grpc.runtime.{GrpcStatus, Stream, VarEventStream}
import io.linkerd.mesh
import io.linkerd.mesh.Converters._

object CodecService {

  def apply(): mesh.Codec.Server = Server

  private[this] object Impl extends mesh.Codec {

    override def readPath(req: mesh.ReadPathReq): Future[mesh.ReadPathRsp] = req match {
      case mesh.ReadPathReq(None) => _transformReadPathRsp(ReturnEmptyPath)
      case mesh.ReadPathReq(Some(txt)) => Future(Path.read(txt)).transform(_transformReadPathRsp)
    }

    override def showPath(req: mesh.ShowPathReq): Future[mesh.ShowPathRsp] = req match {
      case mesh.ShowPathReq(None) => Future.value(mesh.ShowPathRsp(None))
      case mesh.ShowPathReq(Some(p)) => Future.value(mesh.ShowPathRsp(Some(fromPath(p).show)))
    }

    override def readDtab(req: mesh.ReadDtabReq): Future[mesh.ReadDtabRsp] = req match {
      case mesh.ReadDtabReq(None) => _transformReadDtabRsp(ReturnEmptyDtab)
      case mesh.ReadDtabReq(Some(txt)) => Future(Dtab.read(txt)).transform(_transformReadDtabRsp)
    }

    override def showDtab(req: mesh.ShowDtabReq): Future[mesh.ShowDtabRsp] = req match {
      case mesh.ShowDtabReq(None) => Future.value(mesh.ShowDtabRsp(None))
      case mesh.ShowDtabReq(Some(d)) => Future.value(mesh.ShowDtabRsp(Some(fromDtab(d).show)))
    }
  }

  private[this] object Server extends mesh.Codec.Server(Impl)

  private[this] val ReturnEmptyPath = Return(Path.empty)
  private[this] val ReturnEmptyDtab = Return(Dtab.empty)

  private[this] val _transformReadPathRsp: Try[Path] => Future[mesh.ReadPathRsp] = {
    case Return(path) => Future.value(mesh.ReadPathRsp(path = Some(toPath(path))))
    case Throw(exc) => Future.exception(GrpcStatus.InvalidArgument(exc.getMessage))
  }

  private[this] val _transformReadDtabRsp: Try[Dtab] => Future[mesh.ReadDtabRsp] = {
    case Return(dtab) => Future.value(mesh.ReadDtabRsp(dtab = Some(toDtab(dtab))))
    case Throw(exc) => Future.exception(GrpcStatus.InvalidArgument(exc.getMessage))
  }

}
