package io.buoyant.k8s

import com.twitter.conversions.time._
import com.twitter.finagle.{Service => _, _}
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.util.DefaultTimer
import com.twitter.util._
import io.buoyant.k8s.v1._

/**
 * Accepts names in the form:
 *   /<namespace>/<port-name>/<svc-name>/residual/path
 *
 * and attempts to bind an Addr by resolving to the external load balancer
 * for the given service and port.
 */
class ServiceNamer(
  idPrefix: Path,
  labelName: Option[String],
  mkApi: String => NsApi,
  backoff: Stream[Duration] = Backoff.exponentialJittered(10.milliseconds, 10.seconds)
)(implicit timer: Timer = DefaultTimer) extends Namer {

  private[this] val PrefixLen = 3
  private[this] val variablePrefixLength = PrefixLen + labelName.size

  def lookup(path: Path): Activity[NameTree[Name]] = (path.take(variablePrefixLength), labelName) match {
    case (id@Path.Utf8(nsName, portName, serviceName), None) =>
      val nameTree = serviceNs.get(nsName.toLowerCase, None).get(serviceName.toLowerCase, portName.toLowerCase).map(toNameTree(path, _))
      Activity(nameTree.map(Activity.Ok(_)))

    case (id@Path.Utf8(nsName, portName, serviceName, labelValue), Some(label)) =>
      val nameTree = serviceNs
        .get(nsName.toLowerCase, Some(s"$label=$labelValue"))
        .get(serviceName.toLowerCase, portName.toLowerCase)
        .map(toNameTree(path, _))

      Activity(nameTree.map(Activity.Ok(_)))

    case _ =>
      Activity.value(NameTree.Neg)
  }

  private[this] def toNameTree(path: Path, svcAddress: Option[Var[Address]]): NameTree[Name.Bound] = svcAddress match {
    case Some(address) =>
      val residual = path.drop(variablePrefixLength)
      val id = path.take(variablePrefixLength)
      val bound = address.map(Addr.Bound(_))
      NameTree.Leaf(Name.Bound(bound, idPrefix ++ id, residual))
    case None =>
      NameTree.Neg
  }

  private[this] def getPort(service: Service, portName: String): Option[Int] =
    for {
      spec <- service.spec
      port <- spec.ports.find(_.name == portName)
    } yield port.port

  private[this] val serviceNs = new Ns[Service, ServiceWatch, ServiceList, ServiceCache](backoff, timer) {
    override protected def mkResource(name: String) = mkApi(name).services
    override protected def mkCache(name: String) = new ServiceCache(name)
  }
}




