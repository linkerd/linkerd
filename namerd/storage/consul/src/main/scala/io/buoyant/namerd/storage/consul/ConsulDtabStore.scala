package io.buoyant.namerd
package storage.consul

import com.google.common.cache.{CacheBuilder, CacheLoader}
import com.twitter.finagle._
import com.twitter.finagle.http.{MediaType, Request, Response}
import com.twitter.finagle.util.DefaultTimer
import com.twitter.io.Buf
import com.twitter.logging.Logger
import com.twitter.util._
import io.buoyant.admin.Admin
import io.buoyant.config.Parser
import io.buoyant.consul.v1._
import io.buoyant.consul.v1.InstrumentedApiCall.mkPollState
import io.buoyant.namer.InstrumentedVar
import io.buoyant.namerd.DtabStore._
import java.util.concurrent.ConcurrentHashMap
import scala.collection.JavaConverters._

class ConsulDtabStore(
  api: KvApi,
  root: Path,
  datacenter: Option[String] = None,
  readConsistency: Option[ConsistencyMode] = None,
  writeConsistency: Option[ConsistencyMode] = None,
  handlerUrl: String,
  implicit val _timer: Timer = DefaultTimer
) extends DtabStore
  with Admin.WithHandlers {

  private[this] val log = Logger.get("consul")

  private[this] val validNs = raw"^[A-Za-z0-9_-]+".r

  private[this] val dtabStatus = new ConcurrentHashMap[Ns, InstrumentedDtab]()

  def namespaceIsValid(ns: Ns): Boolean = ns match {
    case validNs(_*) => true
    case _ => false
  }

  override val list: Activity[Set[Ns]] = {
    def namespace(key: String): Option[Ns] =
      Some(s"/$key".stripPrefix(s"${root.show}/"))
        // "list()" might return "root" dir or nested dirs - ignore 'em all
        .filterNot(_.isEmpty)
        .filterNot(_.endsWith("/"))
        // avoid awkward situations when we cannot observe listed namespace
        .filter(namespaceIsValid)

    val run = Var.async[Activity.State[Set[Ns]]](Activity.Pending) { updates =>
      @volatile var running = true

      def cycle(index: Option[String], backoffs0: Stream[Duration]): Future[Unit] =
        if (running)
          api.list(
            s"${root.show}/",
            blockingIndex = index,
            datacenter = datacenter,
            consistency = readConsistency,
            retry = true
          )()
            .transform {
              case Return(result) =>
                val namespaces = result.value.flatMap(namespace).toSet
                updates() = Activity.Ok(namespaces)
                cycle(result.index, backoffs0)
              case Throw(e: NotFound) =>
                updates() = Activity.Ok(Set.empty[Ns])
                cycle(e.rsp.headerMap.get(Headers.Index), backoffs0)
              case Throw(e: Failure) if e.isFlagged(FailureFlags.Interrupted) => Future.Done
              case Throw(e) =>
                updates() = Activity.Failed(e)
                log.error("consul ns list observation error %s", e)
                val sleep #:: backoffs1 = backoffs0
                Future.sleep(sleep).before(cycle(None, backoffs1))
            }
        else
          Future.Unit
      val pending = cycle(None, api.backoffs)
      Closable.make { _ =>
        running = false
        pending.raise(Failure("Consul observation released", FailureFlags.Interrupted))
        Future.Unit
      }
    }

    Activity(run).stabilize
  }

  def create(ns: Ns, dtab: Dtab): Future[Unit] = {
    if (!namespaceIsValid(ns)) {
      Future.exception(new DtabNamespaceInvalidException(ns))
    } else {
      api.put(
        s"${root.show}/$ns",
        dtab.show,
        cas = Some("0"),
        datacenter = datacenter,
        consistency = writeConsistency
      )().flatMap { result =>
          if (result) Future.Done else Future.exception(new DtabNamespaceAlreadyExistsException(ns))
        }
    }
  }

  def delete(ns: Ns): Future[Unit] = {
    if (!namespaceIsValid(ns)) {
      Future.exception(new DtabNamespaceInvalidException(ns))
    } else {
      val key = s"${root.show}/$ns"
      api.get(key, datacenter = datacenter, consistency = writeConsistency)()
        .transform {
          case Return(_) => api.delete(
            key,
            datacenter = datacenter,
            consistency = writeConsistency
          )().unit
          case Throw(e: NotFound) => Future.exception(new DtabNamespaceDoesNotExistException(ns))
          case Throw(e) => Future.exception(e)
        }
    }
  }

  def update(ns: Ns, dtab: Dtab, version: Version): Future[Unit] = {
    if (!namespaceIsValid(ns)) {
      Future.exception(new DtabNamespaceInvalidException(ns))
    } else {
      val Buf.Utf8(vstr) = version
      Try(vstr.toLong) match {
        case Return(_) =>
          api.put(
            s"${root.show}/$ns",
            dtab.show,
            cas = Some(vstr),
            datacenter = datacenter,
            consistency = writeConsistency
          )().flatMap { result =>
              if (result) Future.Done else Future.exception(new DtabVersionMismatchException)
            }
        case _ => Future.exception(new DtabVersionMismatchException)
      }
    }
  }

  def put(ns: Ns, dtab: Dtab): Future[Unit] = {
    if (!namespaceIsValid(ns)) {
      Future.exception(new DtabNamespaceInvalidException(ns))
    } else {
      api.put(
        s"${root.show}/$ns",
        dtab.show,
        datacenter = datacenter,
        consistency = writeConsistency
      )().unit
    }
  }

  // We don't hold cached observations open so caching these is very cheap.  Therefore we don't
  // limit the size of this cache.
  private[this] val dtabCache = CacheBuilder.newBuilder()
    .build[Ns, Activity[Option[VersionedDtab]]](
      new CacheLoader[Ns, Activity[Option[VersionedDtab]]] {
        override def load(key: Ns): Activity[Option[VersionedDtab]] = _observe(key)
      }
    )

  def observe(ns: Ns): Activity[Option[VersionedDtab]] = {
    if (namespaceIsValid(ns)) dtabCache.get(ns).stabilize else Activity.exception(new DtabNamespaceInvalidException(ns))
  }

  private[this] def _observe(ns: Ns): Activity[Option[VersionedDtab]] = {
    val key = s"${root.show}/$ns"
    val pollState = mkPollState[Indexed[String]]
    val run = InstrumentedVar[Activity.State[Option[VersionedDtab]]](Activity.Pending) { updates =>
      @volatile var running = true

      def cycle(index: Option[String], backoffs0: Stream[Duration]): Future[Unit] =
        if (running) {
          val apiCall = api.get(
            key,
            blockingIndex = index,
            datacenter = datacenter,
            retry = true,
            consistency = readConsistency
          )
          InstrumentedApiCall.execute(apiCall, pollState)
            .transform {
              case Return(result) =>
                val version = Buf.Utf8(result.index.get)
                // the raw string, not yet parsed as a dtab.
                val rawDtab = result.value
                // attempt to parse the string as a dtab, and update the the
                // Activity with  the new state - either Ok if the string was
                // parsed successfully, or Failed if an error occurred.
                val nextState = Try {
                  Dtab.read(rawDtab)
                } match {
                  case Return(dtab) => // dtab parsing succeeded.
                    Activity.Ok(Some(VersionedDtab(dtab, version)))
                  case Throw(e) => // dtab parsing failed!
                    log.error("consul ns %s dtab parsing failed: %s; dtab: '%s'", ns, e, rawDtab)
                    Activity.Failed(e)
                }
                updates() = nextState
                cycle(result.index, backoffs0)

              case Throw(e: NotFound) =>
                updates() = Activity.Ok(None)
                cycle(e.rsp.headerMap.get(Headers.Index), backoffs0)
              case Throw(e: Failure) if e.isFlagged(FailureFlags.Interrupted) => Future.Done
              case Throw(e) =>
                updates() = Activity.Failed(e)
                log.error("consul ns %s dtab observation error %s", ns, e)
                val sleep #:: backoffs1 = backoffs0
                Future.sleep(sleep).before(cycle(None, backoffs1))

            }
        } else
          Future.Unit
      val pending = cycle(None, api.backoffs)

      Closable.make { _ =>
        running = false
        pending.raise(Failure("Consul observation released", FailureFlags.Interrupted))
        Future.Unit
      }
    }
    dtabStatus.putIfAbsent(ns, InstrumentedDtab(run, pollState))
    Activity(run.underlying).stabilize
  }

  val handlerPrefix = root.show.drop(1) // drop leading "/"

  override def adminHandlers: Seq[Admin.Handler] = Seq(
    Admin.Handler(handlerUrl, new ConsulDtabStoreHandler(dtabStatus.asScala.toMap))
  )
}

private[consul] case class InstrumentedDtab(
  act: InstrumentedVar[Activity.State[Option[VersionedDtab]]],
  state: PollState[String, Indexed[String]]
)

class ConsulDtabStoreHandler(status: => Map[Ns, InstrumentedDtab])
  extends Service[Request, Response] {
  private[this] val mapper = Parser.jsonObjectMapper(Nil)

  override def apply(request: Request): Future[Response] = {
    val state = status.map {
      case (ns, InstrumentedDtab(act, state)) =>
        ns -> Map(
          "state" -> act.stateSnapshot.map {
            case Activity.Ok(Some(dtab)) =>
              Map(
                "version" -> DtabStore.versionString(dtab.version),
                "dtab" -> dtab.dtab.show
              )
            case Activity.Ok(None) => ""
            case Activity.Pending => "Still pending"
            case Activity.Failed(exc) => exc.getMessage
          },
          "poll" -> state
        )
    }

    val res = {
      val r = Response()
      r.mediaType = MediaType.Json
      r.contentString = mapper.writeValueAsString(state)
      r
    }

    Future.value(res)
  }

}
