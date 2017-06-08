package io.buoyant.namerd.iface

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.buoyant.TlsClientConfig
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.param.{HighResTimer, Label}
import com.twitter.finagle.service._
import com.twitter.logging.Logger
import com.twitter.util.{NonFatal => _, _}
import io.buoyant.admin.Admin
import io.buoyant.admin.Admin.{Handler, NavItem}
import io.buoyant.namer.{InterpreterInitializer, NamespacedInterpreterConfig}
import io.buoyant.namerd.iface.{thriftscala => thrift}
import scala.util.control.NonFatal

/**
 * The namerd interpreter offloads the responsibilities of name resolution to
 * the namerd service via the namerd thrift API.  Any namers configured in this
 * linkerd are not used.
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

case class ClientTlsConfig(commonName: String, caCert: Option[String]) {
  def params: Stack.Params = {
    TlsClientConfig(
      disableValidation = Some(false),
      commonName = Some(commonName),
      trustCerts = caCert.map(Seq(_)),
      clientAuth = None
    ).params
  }
}

case class NamerdInterpreterConfig(
  dst: Option[Path],
  namespace: Option[String],
  retry: Option[Retry],
  tls: Option[ClientTlsConfig]
) extends NamespacedInterpreterConfig { config =>

  @JsonIgnore
  private[this] val log = Logger.get()

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
    val label = s"interpreter/${NamerdInterpreterConfig.kind}"

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

    val param.Stats(stats0) = params[param.Stats]
    val stats = stats0.scope(label)

    val tlsParams = tls.map(_.params).getOrElse(Stack.Params.empty)

    val client = ThriftMux.client
      .withParams(ThriftMux.client.params ++ tlsParams ++ params + Thrift.ThriftImpl.Netty4)
      .transformed(retryTransformer)
      .withSessionQualifier.noFailFast
      .withSessionQualifier.noFailureAccrual

    val iface = client.newIface[thrift.Namer.FutureIface](name, label)

    val ns = namespace.getOrElse("default")
    val Label(routerLabel) = params[Label]

    new ThriftNamerClient(iface, ns, stats) with Admin.WithHandlers with Admin.WithNavItems {
      val handler = new NamerdHandler(Seq(routerLabel -> config), Map(routerLabel -> this))

      override def adminHandlers: Seq[Handler] =
        Seq(Handler("/namerd", handler, css = Seq("delegator.css")))

      override def navItems: Seq[NavItem] = Seq(NavItem("namerd", "namerd"))
    }
  }
}

object NamerdInterpreterConfig {
  def kind = classOf[NamerdInterpreterConfig].getCanonicalName
}
