package io.buoyant.linkerd.protocol.h2

import com.twitter.finagle.buoyant.H2
import com.twitter.finagle.buoyant.h2.{Request, Response, Status, Stream}
import com.twitter.finagle.tracing.NullTracer
import com.twitter.finagle.{param => fparam, _}
import com.twitter.util.{Future, Promise}
import java.net.InetSocketAddress
import scala.collection.mutable

case class Downstream(name: String, server: ListeningServer) {
  val address = server.boundAddress.asInstanceOf[InetSocketAddress]
  val port = address.getPort
  val dentry = Dentry(
    Path.read(s"/svs/$name"),
    NameTree.read(s"/$$/inet/127.1/$port")
  )
}

object Downstream {
  def mk(name: String)(f: Request=>Future[Response]): Downstream = {
    val service = Service.mk { req: Request => f(req) }
    val server = H2.server
      .configured(fparam.Label(name))
      .configured(fparam.Tracer(NullTracer))
      .serve(":*", service)
    Downstream(name, server)
  }

  def const(name: String, value: String, status: Status = Status.Ok): Downstream =
    mk(name) { _ =>
      Future.value(Response(status, Stream.const(value)))
    }

  def promise(name: String): (Downstream, mutable.Seq[Promise[Response]]) = {
    val ps = mutable.MutableList[Promise[Response]]()
    val svc = mk(name) { _ =>
      val p = new Promise[Response]()
      ps += p
      p
    }
    (svc, ps)
  }
}
