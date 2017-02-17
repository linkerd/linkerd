package io.buoyant.interpreter

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.buoyant.{H2, TlsClientPrep}
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.util.DefaultTimer
import com.twitter.logging.Logger
import io.buoyant.namer.{InterpreterConfig, InterpreterInitializer}
import io.buoyant.interpreter.mesh.Client
import scala.util.control.NoStackTrace

/**
 * The namerd interpreter offloads the responsibilities of name
 * resolution to the namerd service via the namerd streaming gRPC API.
 * Any namers configured in this linkerd are not used.
 */
class MeshInterpreterInitializer extends InterpreterInitializer {
  val configClass = classOf[MeshInterpreterConfig]
  override def configId: String = "io.l5d.mesh"
}

object MeshInterpreterInitializer extends MeshInterpreterInitializer

object MeshInterpreterConfig {
  private val log = Logger.get(getClass.getName)

  val DefaultRoot = Path.Utf8("default")

  val defaultRetry = Retry(1, 10.minutes.inSeconds)
}

case class Retry(
  baseSeconds: Int,
  maxSeconds: Int
)

case class MeshInterpreterConfig(
  dst: Option[Path],
  root: Option[Path],
  tls: Option[MeshClientTlsConfig],
  retry: Option[Retry]
) extends InterpreterConfig {
  import MeshInterpreterConfig._

  @JsonIgnore
  override val experimentalRequired = true

  /**
   * Construct a namer.
   */
  @JsonIgnore
  def newInterpreter(params: Stack.Params): NameInterpreter = {
    val name = dst match {
      case None => throw new IllegalArgumentException("`dst` is a required field") with NoStackTrace
      case Some(dst) => Name.Path(dst)
    }
    val label = MeshInterpreterInitializer.configId

    val Retry(baseRetry, maxRetry) = retry.getOrElse(defaultRetry)
    val backoffs = Backoff.exponentialJittered(baseRetry.seconds, maxRetry.seconds)

    val client = H2.client
      .withParams(H2.client.params ++ params)
      .transformed(tlsTransformer)
      .newService(name, label)

    root.getOrElse(DefaultRoot) match {
      case r@Path.Utf8(_) =>
        Client(r, client, backoffs, DefaultTimer.twitter)

      case r =>
        val msg = s"io.l5d.mesh: `root` may only contain a single path element (for now): ${r.show}"
        throw new IllegalArgumentException(msg) with NoStackTrace
    }
  }

  @JsonIgnore
  private[this] val tlsTransformer: Stack.Transformer = tls match {
    case None =>
      new Stack.Transformer {
        def apply[Req, Rep](s: Stack[ServiceFactory[Req, Rep]]): Stack[ServiceFactory[Req, Rep]] = s
      }

    case Some(MeshClientTlsConfig(Some(true), _, _)) =>
      new Stack.Transformer {
        override def apply[Req, Rep](s: Stack[ServiceFactory[Req, Rep]]) =
          TlsClientPrep.insecure[Req, Rep] +: s
      }

    case Some(MeshClientTlsConfig(_, Some(cn), certs)) =>
      new Stack.Transformer {
        override def apply[Req, Rep](s: Stack[ServiceFactory[Req, Rep]]) =
          TlsClientPrep.static[Req, Rep](cn, certs) +: s
      }

    case Some(MeshClientTlsConfig(Some(false) | None, None, _)) =>
      val msg = "io.l5d.mesh: tls is configured with validation but `commonName` is not set"
      throw new IllegalArgumentException(msg) with NoStackTrace
  }
}

case class MeshClientTlsConfig(
  disableValidation: Option[Boolean],
  commonName: Option[String],
  trustCerts: Seq[String] = Nil
)
