package io.buoyant.router.thrift

import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.thrift.{Protocols, ThriftClientRequest}
import com.twitter.finagle.thrift.service.ThriftServicePerEndpoint
import com.twitter.finagle.{Path, Service}
import com.twitter.scrooge.ThriftMethod
import com.twitter.util.{Await, Future, Throw}
import io.buoyant.router.RoutingFactory.IdentifiedRequest
import io.buoyant.router.thriftscala.PingService
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class IdentifierTest extends FunSuite with Awaits {

  /** There has GOT to be a better way to encode a thrift request */
  private[this] def encodeRequest(method: ThriftMethod)(args: method.Args): ThriftClientRequest = {
    case class RequestCaptureException(result: ThriftClientRequest) extends Throwable

    val thriftService = Service.mk[ThriftClientRequest, Nothing] { req =>
      Future.exception(RequestCaptureException(req))
    }

    val req = ThriftServicePerEndpoint(method, thriftService, Protocols.binaryFactory(), NullStatsReceiver)(args).transform {
      case Throw(RequestCaptureException(request)) => Future.value(request)
      case _ => fail()
    }
    await(req)
  }

  test("thrift request") {
    val identifier = Identifier(Path.Utf8("thrift"))
    val req = encodeRequest(PingService.Ping)(PingService.Ping.Args("hi"))
    assert(
      await(identifier(req)).asInstanceOf[IdentifiedRequest[ThriftClientRequest]].dst ==
        Dst.Path(Path.read("/thrift"))
    )
  }

  test("thrift request with method") {
    val identifier = Identifier(Path.Utf8("thrift"), methodInDst = true)
    val req = encodeRequest(PingService.Ping)(PingService.Ping.Args("hi"))
    assert(
      await(identifier(req)).asInstanceOf[IdentifiedRequest[ThriftClientRequest]].dst ==
        Dst.Path(Path.read("/thrift/ping"))
    )
  }
}
