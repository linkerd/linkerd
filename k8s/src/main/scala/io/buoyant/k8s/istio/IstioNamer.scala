package io.buoyant.k8s.istio

import com.twitter.finagle._
import com.twitter.finagle.util.DefaultTimer
import com.twitter.util.{Activity, Timer, Var}
import io.buoyant.k8s.log

/**
 * The Istio namer reads service discovery information from the Istio-Pilot's Service Discvoery
 * Service API.
 * https://lyft.github.io/envoy/docs/configuration/cluster_manager/sds_api.html
 * Each lookup is backed by a polling loop.
 */
class IstioNamer(
  discoveryClient: DiscoveryClient,
  idPrefix: Path
)(implicit timer: Timer = DefaultTimer) extends Namer {

  private[this] val PrefixLen = 3
  private[this] val LabelPattern = """(.*):(.*)""".r

  /**
   * Accepts names in the form:
   *   /<cluster>/<labels>/<port-name>/residual/path
   *
   * and initiates a polling loop against the SDS API.  The labels segment must be a :: delimited
   * list of label:value pairs.  To avoid duplication, the labels must be in alphabetical order
   * by label name.  e.g.
   *
   * /foo.default.svc.cluster.local/az:us-west::env:prod::version:v1/http
   *
   * Use :: alone to specify an empty list of labels
   *
   * /foo.default.svc.cluster.local/::/http
   */
  def lookup(path: Path): Activity[NameTree[Name]] = {
    val id = path.take(PrefixLen) match {
      case Path.Utf8(segments@_*) => Path.Utf8(segments.map(_.toLowerCase): _*)
    }
    id match {
      case Path.Utf8(cluster, labels, portName) =>
        val residual = path.drop(PrefixLen)

        val selectors = labels.split("::").collect {
          case LabelPattern(key, value) => key -> value
        }.toMap

        log.debug("SDS lookup: %s %s", id.show, residual.show)

        val vaddr = discoveryClient.watchService(cluster, portName, selectors).run.map {
          case Activity.Ok(rsp) =>
            Addr.Bound(rsp.hosts.map { host =>
              Address(host.ip_address, host.port)
            }.toSet)
          case Activity.Pending =>
            Addr.Pending
          case Activity.Failed(e) =>
            log.debug("SDS lookup failure: %s", e.getMessage)
            Addr.Failed(e)
        }

        // it's okay, scalac.  We all need a little help sometimes.
        val state: Var[Activity.State[NameTree[Name]]] = vaddr.map {
          case Addr.Bound(addrs, _) if addrs.isEmpty =>
            Activity.Ok(NameTree.Neg)
          case Addr.Bound(addrs, _) =>
            Activity.Ok(NameTree.Leaf(Name.Bound(vaddr, idPrefix ++ id, residual)))
          case Addr.Pending =>
            Activity.Pending
          case Addr.Failed(_) =>
            Activity.Ok(NameTree.Neg)
        }

        Activity(state)
      case _ =>
        Activity.value(NameTree.Neg)
    }
  }
}
