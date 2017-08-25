package io.buoyant.namer.dnssrv

import java.net.InetSocketAddress

import com.twitter.finagle._
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.logging.Logger
import com.twitter.util.Activity.State
import com.twitter.util._
import org.xbill.DNS

import scala.collection.concurrent.TrieMap

class DnsSrvNamer(prefix: Path, resolver: DNS.Resolver, timer: Timer, refreshInterval: Duration, stats: StatsReceiver)
  extends Namer {

  private val log = Logger.get("dnssrv")
  private val cache = TrieMap.empty[Path, Var[State[NameTree[Name]]]]

  override def lookup(path: Path): Activity[NameTree[Name]] = path.take(1) match {
    case id@Path.Utf8(address) =>
      log.trace("lookup %s", id)
      val states = cache.getOrElseUpdate(
        id,
        // TrieMap may evaluate this unnecessarily in case of concurrent calls.
        // This is ok, since the body is only run when something subscribes to the Var.
        Var.async[State[NameTree[Name]]](Activity.Pending) { state =>
          timer.schedule(refreshInterval) {
            val next = lookupSrv(address, prefix ++ id, path.drop(2)) match {
              case Return(nameTree) => Activity.Ok(nameTree)
              case Throw(e) => Activity.Failed(e)
            }
            state.update(next)
          }
        }
      )
      Activity(states)
    case _ => Activity.value(NameTree.Neg)
  }

  private[dnssrv] def lookupSrv(address: String, id: Path, residual: Path): Try[NameTree[Name]] = Try {
    val question = DNS.Record.newRecord(
      DNS.Name.fromString(address),
      DNS.Type.SRV,
      DNS.DClass.IN
    )
    val query = DNS.Message.newQuery(question)
    log.debug("looking up %s", address)
    val m = resolver.send(query)
    log.debug("got response %s", address)
    m.getRcode match {
      case DNS.Rcode.NXDOMAIN =>
        log.trace("no results for %s", address)
        NameTree.Neg
      case DNS.Rcode.NOERROR =>
        val hosts = m.getSectionArray(DNS.Section.ADDITIONAL).collect {
          case a: DNS.ARecord => a.getName -> a.getAddress
        }.toMap
        val srvRecords = m.getSectionArray(DNS.Section.ANSWER).collect {
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
          NameTree.Neg
        } else {
          log.trace("got %d results for %s", srvRecords.length, address)
          NameTree.Leaf(Name.Bound(Var.value(Addr.Bound(srvRecords: _*)), id))
        }
      case code =>
        log.trace("unexpected RCODE: %d for %s", code, address)
        NameTree.Fail
    }
  }
}
