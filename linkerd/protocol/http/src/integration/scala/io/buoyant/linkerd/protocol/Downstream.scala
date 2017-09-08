package io.buoyant.linkerd.protocol

import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.ssl.KeyCredentials
import com.twitter.finagle.ssl.server.SslServerConfiguration
import com.twitter.finagle.tracing.NullTracer
import com.twitter.finagle.transport.Transport
import com.twitter.finagle.{Http => FinagleHttp, _}
import com.twitter.util.Future
import java.io.File
import java.net.InetSocketAddress

case class Downstream(name: String, server: ListeningServer) {
  val address = server.boundAddress.asInstanceOf[InetSocketAddress]
  val port = address.getPort
  val dentry = Dentry(
    Path.read(s"/svc/$name"),
    NameTree.read(s"/$$/inet/127.1/$port")
  )
}

object Downstream {
  def mk(name: String)(f: Request => Response): Downstream = {
    val service = Service.mk { req: Request => Future(f(req)) }
    val server = FinagleHttp.server
      .configured(param.Label(name))
      .configured(param.Tracer(NullTracer))
      .serve(":*", service)
    Downstream(name, server)
  }

  def mkTls(name: String, cert: File, key: File)
    (f: Request => Response): Downstream = {
    val service = Service.mk { req: Request => Future(f(req)) }
    val server = FinagleHttp.server
      .configured(param.Label(name))
      .configured(param.Tracer(NullTracer))
      .configured(
        Transport.ServerSsl(Some(SslServerConfiguration(
          keyCredentials = KeyCredentials.CertAndKey(cert, key)
        )))
      )
      .serve(":*", service)
    Downstream(name, server)
  }

  def const(name: String, value: String): Downstream =
    mk(name) { _ =>
      val rsp = Response()
      rsp.contentString = value
      rsp
    }

  def constTls(
    name: String,
    value: String,
    cert: File,
    key: File
  ): Downstream =
    mkTls(name, cert, key) { _ =>
      val rsp = Response()
      rsp.contentString = value
      rsp
    }
}
