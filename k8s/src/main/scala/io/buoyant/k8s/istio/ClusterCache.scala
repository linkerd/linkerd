package io.buoyant.k8s.istio

import com.twitter.logging.Logger
import com.twitter.util.{Closable, Future, Time}

class ClusterCache(client: DiscoveryClient) extends Closable {
  import ClusterCache._

  private[this] val log = Logger()

  private[this] val clusters = client.watchRoutes.map { routes =>
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
    clusters.values.toFuture.flatMap(Future.const).map { dict =>
      dict.get(domain)
    }
  }

  // Hold the clusters Activity open so that it doesn't get restarted for each call to get
  private[this] val closable = clusters.states.respond(_ => ())

  override def close(deadline: Time): Future[Unit] = closable.close(deadline)
}

object ClusterCache {
  case class Cluster(dest: String, port: String)
}
