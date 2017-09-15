package io.buoyant.namer.dnssrv

import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

import com.twitter.finagle._
import com.twitter.finagle.stats.{Stat, StatsReceiver}
import com.twitter.logging.Logger
import com.twitter.util.Activity.State
import com.twitter.util._
import org.xbill.DNS

class DnsSrvNamer(
  prefix: Path,
  resolver: DNS.Resolver,
  origin: DNS.Name,
  refreshInterval: Duration,
  stats: StatsReceiver,
  pool: FuturePool
)(implicit val timer: Timer)
  extends Namer {

  override def lookup(path: Path): Activity[NameTree[Name]] = memoizedLookup(path)

  private[this] val success = stats.counter("lookup_successes_total")
  private[this] val failure = stats.counter("lookup_failures_total")
  private[this] val zeroResults = stats.counter("lookup_zero_results_total")
  private[this] val latency = stats.stat("request_duration_ms")
  private[this] val log = Logger.get("dnssrv")

  private val memoizedLookup: (Path) => Activity[NameTree[Name]] = Memoize { path =>
    path.take(1) match {
      case id@Path.Utf8(address) =>
        Activity(Var.async[State[NameTree[Name]]](Activity.Pending) { state =>

          val done = new AtomicBoolean(false)
          val initialized = new AtomicBoolean(false)

          Future.whileDo(!done.get) {
            lookupSrv(address, prefix ++ id, path.drop(1)).transform { result =>
              result match {
                case Return(nameTree) =>
                  initialized.set(true)
                  state.update(Activity.Ok(nameTree))
                case Throw(e) =>
                  failure.incr()
                  log.error(e, "resolution error: %s", address)
                  if (!initialized.get) {
                    state.update(Activity.Failed(e))
                  }
              }
              Future.sleep(refreshInterval)
            }
          }

          Closable.make { _ =>
            done.set(true)
            Future.Unit
          }

        })
      case _ => Activity.value(NameTree.Neg)
    }
  }

  private def lookupSrv(address: String, id: Path, residual: Path): Future[NameTree[Name]] = {
    val question = DNS.Record.newRecord(
      DNS.Name.fromString(address, origin),
      DNS.Type.SRV,
      DNS.DClass.IN
    )
    val query = DNS.Message.newQuery(question)
    log.debug("looking up %s", address)
    pool {
      val message = Stat.time(latency)(resolver.send(query))
      message.getRcode match {
        case DNS.Rcode.NXDOMAIN =>
          log.trace("no results for %s", address)
          failure.incr()
          NameTree.Neg
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
            // return NameTree.Neg because NameTree.Empty causes requests to fail,
            // even in the presence of load-balancing (NameTree.Union) and fail-over (NameTree.Alt)
            log.trace("empty response for %s", address)
            zeroResults.incr()
            NameTree.Neg
          } else {
            log.trace("got %d results for %s", srvRecords.length, address)
            success.incr()
            NameTree.Leaf(Name.Bound(Var.value(Addr.Bound(srvRecords: _*)), id, residual))
          }
        case code =>
          val msg = s"unexpected RCODE: ${DNS.Rcode.string(code)} for $address"
          throw new IOException(msg)
      }
    }
  }
}
