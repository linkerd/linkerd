package io.buoyant.namer.dnssrv

import java.io.IOException
import java.net.InetSocketAddress

import com.twitter.finagle._
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.logging.Logger
import com.twitter.util.Activity.State
import com.twitter.util._
import org.xbill.DNS

class DnsSrvNamer(prefix: Path, resolver: DNS.Resolver, timer: Timer, refreshInterval: Duration, stats: StatsReceiver)
  extends Namer {

  override def lookup(path: Path): Activity[NameTree[Name]] = memoizedLookup(path)

  private val log = Logger.get("dnssrv")
  private val memoizedLookup: (Path) => Activity[NameTree[Name]] = Memoize { path =>
    path.take(1) match {
      case id@Path.Utf8(address) =>
        Activity(Var.async[State[NameTree[Name]]](Activity.Pending) { state =>
          timer.schedule(refreshInterval) {
            val next = lookupSrv(address, prefix ++ id, path.drop(1)) match {
              case Return(nameTree) => Activity.Ok(nameTree)
              case Throw(e) => Activity.Failed(e)
            }
            state.update(next)
          }
        })
      case _ => Activity.value(NameTree.Neg)
    }
  }

  private[dnssrv] def lookupSrv(address: String, id: Path, residual: Path): Try[NameTree[Name]] = {
    val question = DNS.Record.newRecord(
      DNS.Name.fromString(address),
      DNS.Type.SRV,
      DNS.DClass.IN
    )
    val query = DNS.Message.newQuery(question)
    log.debug("looking up %s", address)
    Try(resolver.send(query)) flatMap { message =>
      log.debug("got response %s", address)
      message.getRcode match {
        case DNS.Rcode.NXDOMAIN =>
          log.trace("no results for %s", address)
          Return(NameTree.Neg)
        case DNS.Rcode.NOERROR =>
          val hosts = message.getSectionArray(DNS.Section.ADDITIONAL).collect {
            case a: DNS.ARecord => a.getName -> a.getAddress
          }.toMap
          val srvRecords = message.getSectionArray(DNS.Section.ANSWER).collect {
            case srv: DNS.SRVRecord =>
              hosts.get(srv.getTarget) match {
                case Some(inetAddress) => Address(new InetSocketAddress(inetAddress, srv.getPort))
                case None => Address(srv.getTarget.toString, srv.getPort)
              }
          }
          if (srvRecords.isEmpty) {
            // valid DNS entry, but no instances.
            // for some reason, NameTree.Empty doesn't work right
            log.trace("empty response for %s", address)
            Return(NameTree.Neg)
          } else {
            log.trace("got %d results for %s", srvRecords.length, address)
            Return(NameTree.Leaf(Name.Bound(Var.value(Addr.Bound(srvRecords: _*)), id, residual)))
          }
        case code =>
          val msg = s"unexpected RCODE: ${DNS.Rcode.string(code)} for $address"
          log.warning(msg)
          Throw(new IOException(msg))
      }
    }
  }
}
