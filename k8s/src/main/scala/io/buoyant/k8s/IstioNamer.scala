package io.buoyant.k8s

import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.util.DefaultTimer
import com.twitter.util.{Activity, Duration, Timer, Var}

/**
 * The Istio namer reads service discovery information from the Istio-Manager's Service Discvoery
 * Service API.
 * https://lyft.github.io/envoy/docs/configuration/cluster_manager/sds_api.html
 * Each lookup is backed by a polling loop.
 */
class IstioNamer(
  sdsClient: SdsClient,
  idPrefix: Path,
  pollInterval: Duration = 5.seconds
)(implicit timer: Timer = DefaultTimer.twitter) extends Namer {

  private[this] val PrefixLen = 4
  private[this] val LabelPattern = """(.*):(.*)""".r

  /**
   * Accepts names in the form:
   *   /<namespace>/<svc-name>/<labels>/<port-name>/residual/path
   *
   * and initiates a polling loop against the SDS API.  The labels segment must be a :: delimited
   * list of label:value pairs.  To avoid duplication, the labels must be in alphabetical order
   * by label name.  e.g.
   *
   * /default/foo/az:us-west::env:prod::version:v1/http
   *
   * Use :: alone to specify an empty list of labels
   *
   * /default/foo/::/http
   */
  def lookup(path: Path): Activity[NameTree[Name]] = {
    val id = path.take(PrefixLen) match {
      case Path.Utf8(segments@_*) => Path.Utf8(segments.map(_.toLowerCase): _*)
    }
    id match {
      case Path.Utf8(nsName, serviceName, labels, portName) =>
        val residual = path.drop(PrefixLen)

        val selectors = labels.split("::").collect {
          case LabelPattern(key, value) => key -> value
        }.toMap

        log.debug("SDS lookup: %s %s", id.show, residual.show)

        val vaddr = sdsClient.watch(nsName, portName, serviceName, selectors, pollInterval).run.map {
          case Activity.Ok(rsp) =>
            Addr.Bound(rsp.hosts.map { host =>
              Address(host.ip_address, host.port)
            }.toSet)
          case Activity.Pending =>
            Addr.Pending
          case Activity.Failed(e) =>
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
