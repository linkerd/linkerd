package io.buoyant.namer.rancher

import com.twitter.finagle._
import com.twitter.finagle.buoyant.ExistentialStability._
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.logging.Logger
import com.twitter.util._
import com.twitter.util.Activity.State

class RancherNamer(
  prefix: Path,
  portMappings: Option[Map[String, Int]],
  maxWait: Int,
  params: Stack.Params,
  stats: StatsReceiver
)(implicit val timer: Timer) extends Namer {
  private[this] val log = Logger.get("io.buoyant.namer.rancher")

  private[this] val ports = Map(
    "http" -> 80,
    "https" -> 443
  ) ++ portMappings.getOrElse(Map())

  private[this] val client = new RancherClient(maxWait, log, params, stats)

  private[this] def lookupPort(port: String): Option[Int] =
    (Try(port.toInt).toOption, ports.get(port)) match {
      case (Some(portNum), _) => Some(portNum)
      case (_, Some(portNum)) => Some(portNum)
      case _ => None
    }

  def lookup(path: Path): Activity[NameTree[Name]] = path.take(3) match {
    case phd@Path.Utf8(port, stack, service) => lookupPort(port) match {
      case Some(portNum) =>
        val containers: Activity[Option[Addr]] = client.activity.map { allContainers =>
          val eligible: Set[Address] = allContainers
            .filter(c => stack.equalsIgnoreCase(c.stackName) && service.equalsIgnoreCase(c.serviceName))
            .flatMap(_.toAddr(portNum))
            .toSet

          Option(eligible).filter(_.nonEmpty).map(Addr.Bound(_))
        }
        val stabilized: Activity[Option[Var[Addr]]] = containers.stabilizeExistence
        stabilized.map {
          case Some(addr) =>
            NameTree.Leaf(Name.Bound(addr, prefix ++ phd, path.drop(3)))
          case None =>
            NameTree.Neg
        }
      case _ =>
        log.warning("unable to understand port: %s", port)
        Activity.value(NameTree.Neg)
    }
    case _ => Activity.value(NameTree.Neg)
  }
}
