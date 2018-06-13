package io.buoyant.linkerd

import com.twitter.finagle._
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.client.Transporter.EndpointAddr
import com.twitter.finagle.naming.buoyant.DstBindingFactory
import com.twitter.util.{Future, Stopwatch}
import io.buoyant.namer.{DelegateTree, Delegator}
import io.buoyant.namer.DelegateTree._
import io.buoyant.router.RoutingFactory.BaseDtab

object RouterContextFormatter {

  private[this] val EmptyDelegateTree = DelegateTree.Empty(Path.empty, Dentry.nop)

  def formatCtx(
    routerLabel: String,
    elapsed: Stopwatch.Elapsed,
    pathCtx: Option[Dst.Path],
    boundCtx: Option[Dst.Bound],
    endpoint: EndpointAddr,
    namers: DstBindingFactory.Namer,
    dtabs: BaseDtab
  ): Future[String] = {
    val serviceName = pathCtx match {
      case Some(dstPath) => dstPath.path
      case None => Path.empty
    }

    val selectedEndpoint = endpoint.addr match {
      case inetAddr: Address.Inet => inetAddr.addr.toString.stripPrefix("/")
      case _ => ""
    }

    val clientPath = boundCtx match {
      case None => Path.empty
      case Some(addrSet) => addrSet.name.id match {
        case p: Path => p
        case _ => Path.empty
      }
    }

    val lbSet = boundCtx match {
      case None => Future.value(Addr.Neg)
      case Some(addrSet) => addrSet.name.addr.changes.toFuture
    }

    val addresses = lbSet.map {
      case Addr.Bound(a, _) => Some(
        a.map {
          case inetAddr: Address.Inet => inetAddr.addr.toString.stripPrefix("/")
          case _ => ""
        }
      )
      case _ => None
    }

    val dtreeF = namers.interpreter match {
      case delegator: Delegator => delegator.delegate(dtabs.dtab(), serviceName)
        .map(Some(_))
      case _ => Future.None
    }

    dtreeF.joinWith(addresses) {
      case (dTree, addrSet) =>
        val tree = DelegateTree.find[Name.Bound](
          dTree.getOrElse(EmptyDelegateTree),
          _.id == clientPath
        ) match {
            case Some(delegation) => delegation.map {
              case (path, "") =>
                s"  ${path.show}"
              case (path, dentry) =>
                s"  ${path.show} ($dentry)"
            }
            case None => Nil
          }
        formatRouterContext(
          routerLabel,
          elapsed,
          serviceName.show,
          clientPath.show,
          addrSet,
          selectedEndpoint,
          tree
        )
    }
  }

  def formatRouterContext(
    routerLabel: String,
    elapsed: Stopwatch.Elapsed,
    serviceName: String,
    clientName: String,
    selectedAddresses: Option[Set[String]],
    selectedAddress: String,
    dtabResolution: List[String]
  ): String = {
    s"""|--- Router: $routerLabel ---
        |request duration: ${elapsed().inMillis.toString} ms
        |service name: $serviceName
        |client name: $clientName
        |addresses: [${selectedAddresses.getOrElse(Set.empty).mkString(", ")}]
        |selected address: $selectedAddress
        |dtab resolution:
        |${dtabResolution.mkString(System.lineSeparator)}
        |""".stripMargin
  }
}
