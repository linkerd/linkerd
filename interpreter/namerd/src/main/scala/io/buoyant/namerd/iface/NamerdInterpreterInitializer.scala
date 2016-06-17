package io.buoyant.namerd.iface

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.conversions.time._
import com.twitter.finagle.Stack.Transformer
import com.twitter.finagle._
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.param.HighResTimer
import com.twitter.finagle.service._
import com.twitter.util._
import io.buoyant.namer.{InterpreterConfig, InterpreterInitializer}
import io.buoyant.namerd.iface.{thriftscala => thrift}
import com.twitter.finagle.buoyant.TlsClientPrep

/**
 * The namerd interpreter offloads the responsibilities of name resolution to
 * the namerd service.  Any namers configured in this linkerd are not used.
 */
class NamerdInterpreterInitializer extends InterpreterInitializer {
  val configClass = classOf[NamerdInterpreterConfig]
  override def configId: String = "io.l5d.namerd"
}

object NamerdInterpreterInitializer extends NamerdInterpreterInitializer

case class Retry(
  baseSeconds: Int,
  maxSeconds: Int
) {
  if (baseSeconds <= 0 || maxSeconds <= 0 || baseSeconds > maxSeconds) {
    val msg = s"illegal retry values: baseSeconds=$baseSeconds maxSeconds=$maxSeconds"
    throw new IllegalArgumentException(msg)
  }
}

case class ClientTlsConfig(commonName: String, caCert: Option[String])

case class NamerdInterpreterConfig(
  dst: Option[Path],
  namespace: Option[String],
  retry: Option[Retry],
  tls: Option[ClientTlsConfig]
) extends InterpreterConfig {

  @JsonIgnore
  val defaultRetry = Retry(5, 10.minutes.inSeconds)

  /**
   * Construct a namer.
   */
  @JsonIgnore
  def newInterpreter(params: Stack.Params): NameInterpreter = {
    val name = dst match {
      case None => throw new IllegalArgumentException("`dst` is a required field")
      case Some(dst) => Name.Path(dst)
    }
    val label = s"namer/${NamerdInterpreterConfig.kind}"

    val Retry(baseRetry, maxRetry) = retry.getOrElse(defaultRetry)
    val backoffs = Backoff.exponentialJittered(baseRetry.seconds, maxRetry.seconds)

    // replaces the client's retry filter with one that retries unconditionally
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
              RetryPolicy.backoff(backoffs) { case (_, Throw(NonFatal(ex))) => true },
              HighResTimer.Default,
              stats,
              RetryBudget.Infinite
            )
            retry.andThen(next)
          }
        }
    }

    val tlsTransformer = new Transformer {
      override def apply[Req, Rep](stack: Stack[ServiceFactory[Req, Rep]]) = {
        tls match {
          case Some(tlsConfig) =>
            TlsClientPrep.static[Req, Rep](tlsConfig.commonName, tlsConfig.caCert) +: stack
          case None => stack
        }
      }
    }

    val client = ThriftMux.client
      .withParams(ThriftMux.client.params ++ params)
      .transformed(retryTransformer)
      .transformed(tlsTransformer)
      .withSessionQualifier.noFailFast
      .withSessionQualifier.noFailureAccrual

    val iface = client.newIface[thrift.Namer.FutureIface](name, label)
    new ThriftNamerClient(iface, namespace.getOrElse("default"))
  }
}

object NamerdInterpreterConfig {
  def kind = classOf[NamerdInterpreterConfig].getCanonicalName
}
