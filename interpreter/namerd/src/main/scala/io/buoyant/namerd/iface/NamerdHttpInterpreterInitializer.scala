package io.buoyant.namerd.iface

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.conversions.DurationOps._
import com.twitter.finagle._
import com.twitter.finagle.buoyant.TlsClientConfig
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.param.HighResTimer
import com.twitter.finagle.service._
import com.twitter.logging.Logger
import com.twitter.util.Throw
import io.buoyant.namer.{InterpreterInitializer, NamespacedInterpreterConfig}
import scala.util.control.NonFatal

/**
 * The namerd interpreter offloads the responsibilities of name resolution to
 * the namerd service via the namerd HTTP streaming API.  Any namers configured
 * in this linkerd are not used.
 */
class NamerdHttpInterpreterInitializer extends InterpreterInitializer {
  val configClass = classOf[NamerdHttpInterpreterConfig]
  override def configId: String = "io.l5d.namerd.http"
}

object NamerdHttpInterpreterInitializer extends NamerdHttpInterpreterInitializer

case class NamerdHttpInterpreterConfig(
  dst: Option[Path],
  namespace: Option[String],
  retry: Option[Retry],
  tls: Option[TlsClientConfig]
) extends NamespacedInterpreterConfig {

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
    val label = s"interpreter/${NamerdHttpInterpreterInitializer.configId}"

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

    val tlsParams = tls.map(_.params).getOrElse(Stack.Params.empty)

    val client = Http.client
      .withParams(Http.client.params ++ tlsParams ++ params + Http.Netty4Impl)
      .withSessionQualifier.noFailFast
      .withSessionQualifier.noFailureAccrual
      .withStreaming(true)
      .transformed(retryTransformer)

    new StreamingNamerClient(client.newService(name, label), namespace.getOrElse("default"))
  }
}
