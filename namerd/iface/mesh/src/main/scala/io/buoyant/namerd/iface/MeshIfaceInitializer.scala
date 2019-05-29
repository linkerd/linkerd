package io.buoyant.namerd
package iface

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle._
import com.twitter.finagle.buoyant.H2
import com.twitter.finagle.buoyant.h2.AccessLogger
import com.twitter.finagle.netty4.ssl.server.Netty4ServerEngineFactory
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.tracing.NullTracer
import com.twitter.logging._
import com.twitter.util.{Return, Throw, Try}
import io.buoyant.grpc.runtime.ServerDispatcher
import io.netty.handler.ssl.ApplicationProtocolNames
import java.net.{InetAddress, InetSocketAddress}

class MeshIfaceConfig extends InterfaceConfig {

  var h2AccessLog: Option[String] = None
  var h2AccessLogRollPolicy: Option[String] = None
  var h2AccessLogAppend: Option[Boolean] = None
  var h2AccessLogRotateCount: Option[Int] = None

  @JsonIgnore
  def mkAccessLogger(
    logFilePath: Option[String],
    logRollPolicy: Option[String],
    logAppend: Option[Boolean],
    logRotateCount: Option[Int]
  ): Option[AccessLogger] = {
    val policy = logRollPolicy match {
      case None => Policy.Never
      case Some(policyName) =>
        Try(Policy.parse(policyName)) match {
          // Default to Never roll policy if we can't parse one from the provided string
          case Throw(_) => Policy.Never
          case Return(r) => r
        }
    }
    val append = logAppend.getOrElse(true)
    val logRotate = logRotateCount.getOrElse(-1)

    val filter = logFilePath match {
      case Some(path) if path != "" =>
        val logger = LoggerFactory(
          node = "access_io.l5d.mesh",
          level = Some(Level.INFO),
          handlers = List(
            FileHandler(
              path,
              policy,
              append,
              logRotate,
              new Formatter(prefix = ""),
              Some(Level.INFO)
            )
          ),
          useParents = false
        )
        Some(AccessLogger(logger()))
      case _ => None
    }
    filter
  }

  @JsonIgnore
  override protected def defaultAddr = MeshIfaceInitializer.defaultAddr

  @JsonIgnore
  override def tlsParams = tls.map(
    _.params(
      Some(Seq(ApplicationProtocolNames.HTTP_2)),
      Netty4ServerEngineFactory()
    )
  ).getOrElse(Stack.Params.empty)

  @JsonIgnore
  override def mk(
    store: DtabStore,
    namers: Map[Path, Namer],
    stats: StatsReceiver
  ): Servable = new Servable {
    def kind = MeshIfaceInitializer.kind
    private[this] val stats1 = stats.scope(kind)

    def serve() = {
      val dispatcher = {
        val codec = mesh.CodecService()
        val interpreter = mesh.InterpreterService(store, namers, stats1)
        val delegator = mesh.DelegatorService(store, namers, stats1)
        val resolver = mesh.ResolverService(namers, stats1)
        val service = ServerDispatcher(codec, interpreter, delegator, resolver)
        mkAccessLogger(
          h2AccessLog,
          h2AccessLogRollPolicy,
          h2AccessLogAppend,
          h2AccessLogRotateCount
        ) match {
          case None => service
          case Some(filter) => filter.andThen(service)
        }
      }

      H2.server
        .withTracer(NullTracer)
        .configuredParams(tlsParams)
        .configuredParams(socketOptParams)
        .withStatsReceiver(stats1)
        .serve(addr, dispatcher)
    }
  }
}

object MeshIfaceInitializer {
  val kind = "io.l5d.mesh"
  val defaultAddr = new InetSocketAddress(InetAddress.getLoopbackAddress, 4321)
}

class MeshIfaceInitializer extends InterfaceInitializer {
  override val configId = MeshIfaceInitializer.kind
  override val configClass = classOf[MeshIfaceConfig]
}
