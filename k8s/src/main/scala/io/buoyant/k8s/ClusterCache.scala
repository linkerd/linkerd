package io.buoyant.k8s

import com.twitter.conversions.time._
import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response}
import com.twitter.logging.Logger
import com.twitter.util.{Duration, Future}
import io.buoyant.k8s.ClusterCache.Cluster

class ClusterCache(client: Service[Request, Response], pollInternal: Duration = 5.seconds) {

  private[this] val log = Logger()
  private[this] val rdsClient = new RdsClient(client)

  private[this] val clusters = rdsClient.watch(pollInternal).map { routes =>
    routes.flatMap(_.virtual_hosts).flatMap { vHost =>
      vHost.name.split('|') match {
        case Array(dest, port) =>
          vHost.domains.map(_ -> Cluster(dest, port))
        case _ => // vHost name is invalid
          log.error(s"Invalid virtual_host name: ${vHost.name}")
          Nil
      }
    }.toMap
  }

  def get(domain: String): Future[Option[Cluster]] = {
    clusters.values.toFuture.flatMap(Future.const).map(_.get(domain))
  }
}

object ClusterCache {
  case class Cluster(dest: String, port: String)
}
