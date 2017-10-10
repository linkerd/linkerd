package io.buoyant.router.thrift

import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.thrift.{Protocols, ThriftClientRequest}
import com.twitter.finagle.{Dtab, Path}
import com.twitter.util.Future
import io.buoyant.router.RoutingFactory
import io.buoyant.router.RoutingFactory.{IdentifiedRequest, RequestIdentification}
import org.apache.thrift.protocol.{TMultiplexedProtocol, TProtocolFactory}
import org.apache.thrift.transport.TMemoryInputTransport

case class Identifier(
  name: Path = Path.empty,
  methodInDst: Boolean = false,
  dtab: () => Dtab = () => Dtab.base,
  protocol: TProtocolFactory = Protocols.binaryFactory()
) extends RoutingFactory.Identifier[ThriftClientRequest] {

  private[this] def suffix(req: ThriftClientRequest): Path = {
    val messageName = protocol.getProtocol(
      new TMemoryInputTransport(req.message)
    ).readMessageBegin().name

    messageName.split(TMultiplexedProtocol.SEPARATOR).toList match {
      case serviceName :: methodName :: Nil => if (methodInDst) {
        Path.read(s"/$serviceName/$methodName")
      } else {
        Path.read(s"/$serviceName")
      }
      case _ => if (methodInDst) {
        Path.read(s"/$messageName")
      } else {
        Path.empty
      }
    }
  }

  def apply(req: ThriftClientRequest): Future[RequestIdentification[ThriftClientRequest]] = {
    val dst = Dst.Path(name ++ Dest.local ++ suffix(req), dtab(), Dtab.local)
    Future.value(new IdentifiedRequest[ThriftClientRequest](dst, req))
  }
}
