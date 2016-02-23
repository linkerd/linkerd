package io.buoyant.router.thrift

import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.thrift.{Protocols, ThriftClientRequest}
import com.twitter.finagle.{Dtab, Path}
import com.twitter.util.Future
import io.buoyant.router.RoutingFactory
import org.apache.thrift.protocol.TProtocolFactory
import org.apache.thrift.transport.TMemoryInputTransport

case class Identifier(
  name: Path = Path.empty,
  methodInDst: Boolean = false,
  dtab: () => Dtab = () => Dtab.base,
  protocol: TProtocolFactory = Protocols.binaryFactory()
) extends RoutingFactory.Identifier[ThriftClientRequest] {

  private[this] def suffix(req: ThriftClientRequest): Path = {
    if (methodInDst) {
      val messageName = protocol.getProtocol(
        new TMemoryInputTransport(req.message)
      ).readMessageBegin().name
      Path.read(s"/$messageName")
    } else {
      Path.empty
    }
  }

  def apply(req: ThriftClientRequest): Future[Dst] =
    Future.value(Dst.Path(name ++ suffix(req), dtab(), Dtab.local))
}
