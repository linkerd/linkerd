package io.buoyant.namerd.storage

import com.twitter.finagle.{Http, Dtab => FDtab}
import com.twitter.io.Buf
import com.twitter.logging.Logger
import com.twitter.util._
import io.buoyant.k8s.ObjectMeta
import io.buoyant.namerd.DtabStore.{DtabNamespaceAlreadyExistsException, DtabNamespaceDoesNotExistException, DtabVersionMismatchException}
import io.buoyant.namerd.storage.kubernetes._
import io.buoyant.namerd.{Ns, DtabStore, VersionedDtab}
import java.util.concurrent.atomic.AtomicReference
import scala.collection.breakOut

/**
 * A DtabStore using the
 * [[https://github.com/kubernetes/kubernetes/blob/master/docs/design/extending-api.md Kubernetes third-party API]]
 * (in beta as of Kubernetes 1.2) to store Dtabs. Note that usage of this store will require some
 * setup:
 *
 * `kubectl create namerd/examples/k8s/3rdparty.yaml`
 *
 * This creates a third-party resource at: /apis/buoyant.io/v1/dtabs.
 *
 * TODO: add tests: unit, end-to-end
 *
 * @param client    a fully configured client which will be used to access the Kubernetes API
 * @param dst       the destination which will be used to construct a service from the client
 * @param namespace the Kubernetes namespace being read/written by this store (note that this
 *                  differs from Dtab namespaces)
 */
class K8sDtabStore(client: Http.Client, dst: String, namespace: String)
  extends DtabStore {
  private[this] val log = Logger()

  // NOTE: we construct two clients, one for the streaming watch requests, one for everything else.
  // This is due to an as-yet-undetermined issue where the first non-watch request will hang if
  // using the same client.
  val api: NsApi = Api(client.newService(dst)).withNamespace(namespace)
  val watchApi: NsApi = Api(client.newService(dst)).withNamespace(namespace)

  private[this] def name(dtab: Dtab): String = {
    dtab.metadata.flatMap(_.name).getOrElse {
      throw new IllegalStateException("k8s API returned a dtab with no name: $dtab")
    }
  }

  private[this] def toDtabMap(dtab: Dtab): (String, VersionedDtab) = {
    val vsn = dtab.metadata.flatMap(_.resourceVersion).getOrElse {
      throw new IllegalStateException("k8s API returned a resource with no version")
    }
    val versionedDtab = VersionedDtab(FDtab(dtab.dentries.toIndexedSeq), Buf.Utf8(vsn))
    (name(dtab), versionedDtab)
  }

  private[this]type NsMap = Map[String, VersionedDtab]
  private[this] val dtabListToNsMap: Option[DtabList] => NsMap = {
    case Some(dtabs) => dtabs.items.map(toDtabMap)(breakOut)
    case None =>
      // NOTE: i'm not 100% sure this is the right behaviour here, but i'm assuming
      //       based on the exceptions in `toDtabMap` and `name` that we want to fail
      //       fast here rather than wait for a dtab list that doesn't currently exist
      //       to be created?
      throw new IllegalStateException("k8s API request for Dtab list returned 404!")
  }

  // Set up a watch on the Kubernetes API to update our internal state
  // NOTE: this currently relies on our watch connection to update the internal state, rather than
  // doing any kind of "write-through caching", so there are currently no "read-your-write"
  // semantics. If those are required, we would probably need to implement a synchronized means of
  // reading `act` and using that value to feed to `witness`, rather than taking the `foldLeft`
  // approach as below.
  private[this] val act: Activity[NsMap] =
    watchApi.dtabs.activity(dtabListToNsMap) {
      (nsMap: NsMap, watchEvent) =>
        watchEvent match {
          case DtabAdded(a) => nsMap + toDtabMap(a)
          case DtabModified(m) => nsMap + toDtabMap(m)
          case DtabDeleted(d) => nsMap - name(d)
          case DtabError(e) =>
            log.error("k8s watch error: %s", e)
            nsMap
        }
    }

  /** List all Dtab namespaces */
  def list(): Activity[Set[Ns]] = act.map(_.keySet)

  private[this] def namedDtab(ns: String, dtab: FDtab, version: Option[String] = None): Dtab = Dtab(
    dentries = dtab,
    apiVersion = Some(api.version),
    kind = Some("DTab"),
    metadata = Some(ObjectMeta(
      name = Some(ns),
      resourceVersion = version
    ))
  )

  /**
   * Update an existing dtab.  Returns a DtabVersionMismatchException if the
   * supplied version doesn't match the current version.
   */
  def update(ns: String, dtab: FDtab, version: Buf): Future[Unit] = {
    val versionStr = version match {
      case Buf.Utf8(str) => str
      case _ =>
        throw new IllegalArgumentException(s"invalid or non-UTF8 version string: ${version.toString}")
    }

    api.dtabs.named(ns).put(namedDtab(ns, dtab, Some(versionStr))).unit.rescue {
      case io.buoyant.k8s.Api.Conflict(_) => Future.exception(new DtabVersionMismatchException())
    }
  }

  /**
   * Update an existing dtab based on the current version or create a new
   * dtab if one doesn't already exist.
   */
  def put(ns: String, dtab: FDtab): Future[Unit] = {
    val nsDTab = api.dtabs.named(ns)
    nsDTab.get().flatMap {
      case Some(value) =>
        val resourceVersion = for {
          meta <- value.metadata
          version <- meta.resourceVersion
        } yield version
        nsDTab.put(namedDtab(ns, dtab, resourceVersion)).unit.rescue {
          // In the event of a 404 on a PUT, we should POST instead, as the k8s API doesn't appear to
          // support creating on PUT. Note that this is potentially racy if the namespace is created
          // between the PUT and the POST, but it's likely safer to surface that error all the way to
          // the user in that case than to attempt to recover beyond this point.
          case io.buoyant.k8s.Api.NotFound(_) => create(ns, dtab)
        }
      case None => create(ns, dtab)
    }
  }

  /** Watch a dtab and its version. */
  def observe(ns: String): Activity[Option[VersionedDtab]] =
    act.map(_.get(ns))

  /**
   * Create a new dtab.  Returns a DtabNamespaceAlreadyExistsException if a
   * dtab already exists with that namespace.
   */
  def create(ns: String, dtab: FDtab): Future[Unit] = {
    api.dtabs.post(namedDtab(ns, dtab)).unit.rescue {
      case io.buoyant.k8s.Api.Conflict(_) =>
        Future.exception(new DtabNamespaceAlreadyExistsException(ns))
    }
  }

  /**
   * Deletes a dtab.  Returns a DtabNamespaceDoesNotExistException if the
   * namespace does not exist.
   */
  override def delete(ns: String): Future[Unit] = {
    api.dtabs.named(ns).delete.unit.rescue {
      case io.buoyant.k8s.Api.NotFound(_) =>
        Future.exception(new DtabNamespaceDoesNotExistException(ns))
    }
  }
}
