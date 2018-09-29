package io.buoyant.namer.dnssrv

import java.net.{InetAddress, InetSocketAddress, UnknownHostException}
import java.util.concurrent.atomic.AtomicInteger

import com.twitter.finagle._
import com.twitter.finagle.stats.{Stat, StatsReceiver}
import com.twitter.logging.Logger
import com.twitter.util._
import org.xbill.DNS

import scala.util.control.NoStackTrace

class DnsSrvNamer(
  prefix: Path,
  resolver: () => DNS.Resolver,
  refreshInterval: Duration,
  stats: StatsReceiver
)(implicit val timer: Timer)
  extends Namer {

  override def lookup(path: Path): Activity[NameTree[Name]] = memoizedLookup(path)

  private[this] val success = stats.counter("lookup_successes_total")
  private[this] val failure = stats.counter("lookup_failures_total")
  private[this] val dnsscope = stats.scope("lookup_dns")
  private[this] val dnsIter = dnsscope.scope("iteration")
  private[this] val dnsSuccess = dnsscope.scope("success")
  private[this] val dnsFailed = dnsscope.scope("failed")
  private[this] val dnsNoCode = dnsscope.scope("nocode")

  private[this] val zeroResults = stats.counter("lookup_zero_results_total")
  private[this] val badHosts = stats.counter("unknown_srv_hosts_results_total")
  private[this] val unexpectedCode = stats.counter("unexpected_code_total")

  private[this] val latency = stats.stat("request_duration_ms")
  private[this] val log = Logger.get("dnssrv")

  private val memoizedLookup: (Path) => Activity[NameTree[Name]] = Memoize { path =>
    path.take(1) match {
      case id@Path.Utf8(address) =>
        val vaddr = watchDns(address, timer).run.map {
          case Activity.Ok(rsp) =>
            Addr.Bound(rsp: _*)
          case Activity.Pending =>
            Addr.Pending
          case Activity.Failed(e) =>
            log.debug("SRV lookup failure: %s address: %s pattern: %s", e.getMessage, address, id)
            Addr.Failed(e)
        }

        val state: Var[Activity.State[NameTree[Name]]] = vaddr.map {
          case Addr.Bound(addrs, _) if addrs.isEmpty =>
            Activity.Ok(NameTree.Neg)
          case Addr.Bound(addrs, _) =>
            Activity.Ok(NameTree.Leaf(Name.Bound(vaddr, prefix ++ id, path.drop(1))))
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

  private def watchDns(dnsSrvName: String, timer: Timer): Activity[List[Address]] = {
    val iteration = new AtomicInteger(0)
    val gauge = dnsIter.addGauge(dnsSrvName)(iteration.floatValue) // emit PER DNS iteration count, ref this val in the doUnit to avoid GC

    val state: Var[Activity.State[List[Address]]] = Var.async[Activity.State[List[Address]]](Activity.Pending) { update =>
      def doUnit(): Unit = {
        val lookup = new DNS.Lookup(dnsSrvName, DNS.Type.SRV, DNS.DClass.IN)
        iteration.incrementAndGet()
        lookup.setResolver(resolver())
        lookup.setCache(null)
        Stat.time(latency)(lookup.run())
        log.debug("dns: %s lookup %s iteration: %s", dnsSrvName, lookup.toString, iteration)
        lookup.getResult match {
          case DNS.Lookup.HOST_NOT_FOUND | DNS.Lookup.TYPE_NOT_FOUND =>
            val msg = s"no results for $dnsSrvName return type: ${lookup.getResult} errorString: ${lookup.getErrorString} iteration: ${iteration.intValue()}"
            log.debug(s"in doUnit: message %s", msg)
            failure.incr()
            update.update(Activity.Failed(new DNSLookupException(msg)))

          case DNS.Lookup.SUCCESSFUL =>
            val answers = Option(lookup.getAnswers).getOrElse(Array.empty)
            val srvRecords = answers.flatMap {
              case srv: DNS.SRVRecord => try {
                val inetAddress = InetAddress.getByName(srv.getTarget.toString())
                Some(Address(new InetSocketAddress(inetAddress, srv.getPort)))
              } catch {
                case _: UnknownHostException =>
                  log.warning(s"srv lookup of $dnsSrvName returned unknown host ${srv.getTarget}")
                  badHosts.incr()
                  None
              }
              case _ => None
            }
            if (srvRecords.isEmpty) {
              // valid DNS entry, but no instances.
              // return NameTree.Neg because NameTree.Empty causes requests to fail,
              // even in the presence of load-balancing (NameTree.Union) and fail-over (NameTree.Alt)
              val msg = s"empty response for $dnsSrvName iteration: ${iteration.intValue()}"
              log.debug(s"in doUnit: message %s ", msg)
              zeroResults.incr()
              update.update(Activity.Failed(new DNSLookupException(msg)))
            } else {
              log.trace("got %d results for %s", srvRecords.length, dnsSrvName)
              success.incr()
              update.update(Activity.Ok(srvRecords.toList))
            }
          case code =>
            val msg = s"unexpected result: $code for $dnsSrvName: errorString: ${lookup.getErrorString} iteration: ${iteration.intValue()}"
            log.error(msg)
            unexpectedCode.incr()
            update.update(Activity.Failed(new DNSLookupException(msg)))
        }
      }
      doUnit()

      timer.schedule(refreshInterval) {
        doUnit()
      }
    }
    Activity(state)
  }
}

case class DNSLookupException(msg: String)
  extends Exception(msg)
  with NoStackTrace
