/** Modified from com.twitter.finagle.netty4.ssl */
package com.twitter.finagle.netty4.buoyant

import com.twitter.finagle.buoyant.TlsClientPrep
import com.twitter.finagle.client.Transporter
import com.twitter.finagle.netty4.ssl.SslConnectHandler
import com.twitter.finagle.ssl.SessionVerifier
import com.twitter.finagle.transport.{TlsConfig, Transport}
import com.twitter.finagle.{Address, Stack}
import io.netty.channel._
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.handler.ssl.{ApplicationProtocolConfig, SslContext, SslContextBuilder, SslHandler}
import io.netty.util.concurrent.{Future => NettyFuture, GenericFutureListener}
import java.io.File
import javax.net.ssl.SSLContext
import scala.collection.JavaConverters._

/**
 * An internal channel handler that reads the Transport.Tls and
 * [[ApplicationProtocols]] stack params to replace the
 * finagle-installed SSL channel handlers for client transport
 * encryption.
 *
 * This is a workaround for a limited set of TlsConfig options:
 * application protocols may be configured on servers, but not on
 * clients. The introduction of the [[ApplicationProtocols]] param
 * allows finagle transporters to announce protocol support.
 *
 * See https://github.com/twitter/finagle/issues/580
 */
object Netty4ClientTls {
  case class ApplicationProtocols(protocols: Seq[String])

  implicit object ApplicationProtocols extends Stack.Param[ApplicationProtocols] {
    val default = ApplicationProtocols(Nil)

    def apply(hd: String, tl: String*): ApplicationProtocols =
      ApplicationProtocols(hd +: tl)

    private[Netty4ClientTls] def mk(params: Stack.Params): ApplicationProtocolConfig =
      params[ApplicationProtocols].protocols match {
        case Nil => ApplicationProtocolConfig.DISABLED
        case protos =>
          new ApplicationProtocolConfig(
            ApplicationProtocolConfig.Protocol.NPN_AND_ALPN,
            ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
            ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
            protos.asJava
          )
      }
  }

  def handler(params: Stack.Params): ChannelInitializer[Channel] =
    new Handler(params)

  private class Handler(params: Stack.Params) extends ChannelInitializer[Channel] {

    override def initChannel(ch: Channel): Unit =
      params[TlsClientPrep.TransportSecurity].config match {
        case TlsClientPrep.TransportSecurity.Insecure =>

        case TlsClientPrep.TransportSecurity.Secure() =>
          val ctx = {
            val ctxb = SslContextBuilder.forClient()
              .applicationProtocolConfig(ApplicationProtocols.mk(params))

            params[TlsClientPrep.Trust].config match {
              case TlsClientPrep.Trust.NotConfigured =>
                throw new IllegalStateException("trust not configured")

              case TlsClientPrep.Trust.Verified(_, certs) =>
                ctxb.trustManager(certs: _*).build()

              case TlsClientPrep.Trust.UnsafeNotVerified =>
                ctxb.trustManager(InsecureTrustManagerFactory.INSTANCE).build()
            }
          }

          val handler = params[Transporter.EndpointAddr] match {
            case Transporter.EndpointAddr(Address.Inet(isa, _)) =>
              ctx.newHandler(ch.alloc(), isa.getHostName, isa.getPort)
            case _ => ctx.newHandler(ch.alloc())
          }
          handler.engine.setUseClientMode(true)

          // Cargo-culted from finagle:
          //
          // The reason we can't close the channel immediately is because
          // we're in process of decoding an inbound message that is
          // represented by a bunch of TLS records. We need to finish
          // decoding and send that message up to the pipeline before
          // closing the channel. This is why we queue the close event.
          val closeChannelOnCloseNotify =
            new GenericFutureListener[NettyFuture[Channel]] {
              def operationComplete(f: NettyFuture[Channel]): Unit = {
                val channel = f.getNow
                channel.eventLoop.execute(new Runnable {
                  def run(): Unit = { channel.close(); () }
                })
              }
            }

          // Close channel on close_notify received from a remote peer.
          handler.sslCloseFuture.addListener(closeChannelOnCloseNotify)

          val validator = params[TlsClientPrep.Trust].config match {
            case TlsClientPrep.Trust.Verified(cn, _) => SessionVerifier.hostname(cn)
            case _ => SessionVerifier.AlwaysValid
          }
          ch.pipeline.addFirst("tlsConnect", new SslConnectHandler(handler, validator))

          ch.pipeline.addFirst("tls", handler); ()
      }
  }
}
