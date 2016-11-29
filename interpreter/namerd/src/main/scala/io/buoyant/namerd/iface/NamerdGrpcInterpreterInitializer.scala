package io.buoyant.namerd.iface

import com.twitter.conversions.time._
import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle._
import com.twitter.finagle.client.StackClient
import com.twitter.finagle.buoyant.{H2, TlsClientPrep}
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.param.HighResTimer
import com.twitter.finagle.service._
import com.twitter.logging.Logger
import com.twitter.util.Throw
import io.buoyant.namer.{InterpreterConfig, InterpreterInitializer}
import io.buoyant.namerd.iface.grpc.InterpreterClient
import io.buoyant.proto.namerd.Interpreter
import scala.util.control.NonFatal

/**
 * The namerd interpreter offloads the responsibilities of name
 * resolution to the namerd service via the namerd streaming gRPC API.
 * Any namers configured in this linkerd are not used.
 */
class NamerdGrpcInterpreterInitializer extends InterpreterInitializer {
  val configClass = classOf[NamerdGrpcInterpreterConfig]
  override def configId: String = "io.l5d.namerd.grpc"
}

object NamerdGrpcInterpreterInitializer extends NamerdGrpcInterpreterInitializer

case class NamerdGrpcInterpreterConfig(
  dst: Option[Path],
  namespace: Option[String],
  retry: Option[Retry],
  tls: Option[ClientTlsConfig]
) extends InterpreterConfig {

  @JsonIgnore
  private[this] val log = Logger.get()

  @JsonIgnore
  val defaultRetry = Retry(5, 10.minutes.inSeconds)

  @JsonIgnore
  override val experimentalRequired = true

  /**
   * Construct a namer.
   */
  @JsonIgnore
  def newInterpreter(params: Stack.Params): NameInterpreter = {
    val name = dst match {
      case None => throw new IllegalArgumentException("`dst` is a required field")
      case Some(dst) => Name.Path(dst)
    }
    val label = NamerdGrpcInterpreterInitializer.configId

    val Retry(baseRetry, maxRetry) = retry.getOrElse(defaultRetry)
    val backoffs = Backoff.exponentialJittered(baseRetry.seconds, maxRetry.seconds)
    val retryTransformer = new Stack.Transformer {
      def apply[Req, Rsp](stk: Stack[ServiceFactory[Req, Rsp]]) =
        stk.replace(Retries.Role, module[Req, Rsp])

      def module[Req, Rsp]: Stackable[ServiceFactory[Req, Rsp]] =
        new Stack.Module1[param.Stats, ServiceFactory[Req, Rsp]] {
          val role = Retries.Role
          val description = "Retries on any non-fatal error"
          def make(_stats: param.Stats, next: ServiceFactory[Req, Rsp]) = {
            val param.Stats(stats) = _stats
            val retry = new RetryFilter[Req, Rsp](
              RetryPolicy.backoff(backoffs) {
                case (_, Throw(NonFatal(ex))) =>
                  log.error(ex, "namerd request failed")
                  true
              },
              HighResTimer.Default,
              stats,
              RetryBudget.Infinite
            )
            retry.andThen(next)
          }
        }
    }

    val clientParams = H2.client.params ++ params

    val client = {
      val c = H2.client
        .withParams(H2.client.params ++ params)
        .transformed(retryTransformer)
      configureTls(c, tls).newService(name, label)
    }

    InterpreterClient(new Interpreter.Client(client), namespace.getOrElse("default"))
  }

  @JsonIgnore
  private[this] def configureTls[Req, Rsp](client: StackClient[Req, Rsp], tls: Option[ClientTlsConfig]): StackClient[Req, Rsp] =
    tls match {
      case None => client
      case Some(config) =>
        val cn = config.commonName
        val certs = config.caCert.toSeq.map(TlsClientPrep.loadCert(_))
        client.configured(TlsClientPrep.TransportSecurity(TlsClientPrep.TransportSecurity.Secure()))
          .configured(TlsClientPrep.Trust(TlsClientPrep.Trust.Verified(cn, certs)))
    }
}
