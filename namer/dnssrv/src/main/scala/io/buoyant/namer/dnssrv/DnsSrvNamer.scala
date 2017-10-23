package io.buoyant.namer.dnssrv

import java.io.IOException
import java.net.{InetAddress, InetSocketAddress, UnknownHostException}
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
  refreshInterval: Duration,
  stats: StatsReceiver,
  pool: FuturePool
)(implicit val timer: Timer)
  extends Namer {

  override def lookup(path: Path): Activity[NameTree[Name]] = memoizedLookup(path)

  private[this] val success = stats.counter("lookup_successes_total")
  private[this] val failure = stats.counter("lookup_failures_total")
  private[this] val zeroResults = stats.counter("lookup_zero_results_total")
  private[this] val badHosts = stats.counter("unknown_srv_hosts_results_total")
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
    log.debug("looking up %s", address)
    pool {
      val lookup = new DNS.Lookup(address, DNS.Type.SRV, DNS.DClass.IN)
      lookup.setResolver(resolver)
      Stat.time(latency)(lookup.run())
      lookup.getResult match {
        case DNS.Lookup.HOST_NOT_FOUND | DNS.Lookup.TYPE_NOT_FOUND =>
          log.trace("no results for %s", address)
          failure.incr()
          NameTree.Neg
        case DNS.Lookup.SUCCESSFUL =>
          val answers = Option(lookup.getAnswers).getOrElse(Array.empty)
          val srvRecords = answers.flatMap {
            case srv: DNS.SRVRecord => try {
              val inetAddress = InetAddress.getByName(srv.getTarget.toString())
              Some(Address(new InetSocketAddress(inetAddress, srv.getPort)))
            } catch {
              case _: UnknownHostException =>
                log.warning(s"srv lookup of $address returned unknown host ${srv.getTarget}")
                badHosts.incr()
                None
            }
            case _ => None
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
          val msg = s"unexpected result: $code for $address: ${lookup.getErrorString}"
          log.error(msg)
          throw new IOException(msg)
      }
    }
  }
}
