package io.buoyant.k8s

import com.fasterxml.jackson.core.`type`.TypeReference
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.stats.{DefaultStatsReceiver, StatsReceiver}
import com.twitter.finagle.tracing.Trace
import com.twitter.finagle.{Filter, http}
import com.twitter.util.TimeConversions._
import com.twitter.util.{Closable, _}

/**
 * Basic functionality shared by all k8s Resource implementations: they require an HTTP client,
 * and can be closed.
 */
trait Resource extends Closable {
  def client: Client
  def close(deadline: Time) = client.close(deadline)
}

/**
 * A Version is a top-level API grouping within the K8s API; examples include "/api/v1" and
 * "/apis/extensions/v1beta1".
 *
 * Implementors are likely to want to extend this to provide named convenience methods on top of the
 * [[Version.listResource]] method and to type-specialize on the resource types supported by that
 * version.
 *
 * @tparam O The parent type for all [Object]s served by this version.
 */
private[k8s] trait Version[O <: KubeObject] extends Resource {
  val path = s"/$group/$version"

  /**
   * The first portion of the API path. Currently-known groups are "api" (for the core k8s v1 API)
   * and "apis" (all others, including extensions and third-party resources).
   */
  def group: String

  /**
   * The second portion of the API path. examples include "v1", "extensions/v1beta1", "buoyant.io/v1".
   */
  def version: String

  def withNamespace(ns: String) = new NsVersion[O](client, group, version, ns)
  def withNamespaceWatch(ns: String) = new NsWatchVersion[O](client, group, version, ns)

  def listResource[T <: O: TypeReference, W <: Watch[T]: TypeReference, L <: KubeList[T]: TypeReference](
    backoffs: Stream[Duration] = Backoff.exponentialJittered(1.milliseconds, 5.seconds),
    stats: StatsReceiver = DefaultStatsReceiver
  )(implicit od: ObjectDescriptor[T, W]) = new ListResource[T, W, L](client, path, backoffs, stats)
}

/**
 * A Version with a namespace applied. This is a Class because we want to provide a default
 * `namespace` implementation in [[Version.listResource]], although in many cases implementors may wish to
 * extend this to provide convenience methods wrapping `list`.
 *
 * @param client See [[Version.client]]
 * @param group See [[Version.group]]
 * @param version See [[Version.version]]
 * @param ns The namespace
 * @tparam O The parent type for all [[KubeObject]]s served by this version.
 */
private[k8s] class NsVersion[O <: KubeObject](
  val client: Client,
  group: String,
  val version: String,
  ns: String
)
  extends Resource {
  val path = s"/$group/$version/namespaces/$ns"

  def listResource[T <: O: TypeReference, W <: Watch[T]: TypeReference, L <: KubeList[T]: TypeReference](
    backoffs: Stream[Duration] = Backoff.exponentialJittered(1.milliseconds, 5.seconds),
    stats: StatsReceiver = DefaultStatsReceiver
  )(implicit od: ObjectDescriptor[T, W]) = new NsListResource[T, W, L](client, path, backoffs, stats)

  def objectResource[T <: O: TypeReference, W <: Watch[T]: TypeReference](
    name: String,
    backoffs: Stream[Duration] = Backoff.exponentialJittered(1.milliseconds, 5.seconds),
    stats: StatsReceiver = DefaultStatsReceiver
  )(implicit od: ObjectDescriptor[T, W]) = {
    val listName = implicitly[ObjectDescriptor[T, W]].listName
    new NsObjectResource[T, W](client, s"$path/$listName", name, backoffs, stats)
  }
}

/**
 * A Version with a watch and a namespace applied. This Class provides the ability to watch
 * individual objects in a namespace. To watch lists of objects, use [[Version]] or [[NsVersion]].
 * TODO: Consider moving all watches to this class?
 *
 * @param client See [[Version.client]]
 * @param group See [[Version.group]]
 * @param version See [[Version.version]]
 * @param ns The namespace
 * @tparam O The parent type for all [[KubeObject]]s served by this version.
 */
private[k8s] class NsWatchVersion[O <: KubeObject](
  val client: Client,
  group: String,
  val version: String,
  ns: String
)
  extends Resource {
  val path = s"/$group/$version/watch/namespaces/$ns"
  val nonWatchPath = s"/$group/$version/namespaces/$ns"

  def objectResource[T <: O: TypeReference, W <: Watch[T]: TypeReference](
    name: String,
    backoffs: Stream[Duration] = Backoff.exponentialJittered(1.milliseconds, 5.seconds),
    stats: StatsReceiver = DefaultStatsReceiver
  )(implicit od: ObjectDescriptor[T, W]) = {
    val listName = implicitly[ObjectDescriptor[T, W]].listName
    new NsObjectResource[T, W](client, s"$path/$listName", name, backoffs, stats, Some(s"$nonWatchPath/$listName"))
  }
}

/**
 * A third-party version contains ThirdPartyResource-typed objects. See
 * https://github.com/kubernetes/kubernetes/blob/master/docs/design/extending-api.md for a description of the
 * design model.
 *
 * Implementors are likely to want to extend this to provide convenience methods on top of the `list` method.
 *
 * @tparam O The parent type for all [[KubeObject]]s served by this Version.
 */
trait ThirdPartyVersion[O <: KubeObject] extends Version[O] {
  /** domain name, i.e. "buoyant.io" */
  def owner: String

  /** version within owner domain, i.e. "v1" */
  def ownerVersion: String

  def version: String = ThirdPartyVersion.version(owner, ownerVersion)
  def basePath: String = s"/${ThirdPartyVersion.group}/$version"
  val group = ThirdPartyVersion.group
  override def withNamespace(ns: String) = new NsThirdPartyVersion[O](client, owner, ownerVersion, ns)
}

object ThirdPartyVersion {
  def version(owner: String, ownerVersion: String) = s"$owner/$ownerVersion"
  val group = "apis"
}

/**
 * A namespaced third-party version, i.e. "/apis/buoyant.io/v1/namespaces/ns/".
 */
class NsThirdPartyVersion[O <: KubeObject](client: Client, owner: String, ownerVersion: String, ns: String)
  extends NsVersion[O](client, ThirdPartyVersion.group, ThirdPartyVersion.version(owner, ownerVersion), ns)

/**
 * Represents the functionality for a  kubernetes API resource serving a list of objects, for example:
 * `/api/v1/namespaces/{namespace}/endpoints`.
 */
private[k8s] class ListResource[O <: KubeObject: TypeReference, W <: Watch[O]: TypeReference, L <: KubeList[O]: TypeReference](
  val client: Client,
  basePath: String,
  protected val backoffs: Stream[Duration] = Watchable.DefaultBackoff,
  protected val stats: StatsReceiver = DefaultStatsReceiver
)(implicit od: ObjectDescriptor[O, W]) extends Watchable[O, W] with Resource {
  val name = implicitly[ObjectDescriptor[O, W]].listName
  val path = s"$basePath/$name"

  def get(
    labelSelector: Option[String] = None,
    fieldSelector: Option[String] = None,
    resourceVersion: Option[String] = None,
    retryIndefinitely: Boolean = false
  ): Future[L] = {
    val req = Api.mkreq(http.Method.Get, this.path, None,
      "labelSelector" -> labelSelector,
      "fieldSelector" -> fieldSelector,
      "resourceVersion" -> resourceVersion)
    val retry = if (retryIndefinitely) infiniteRetryFilter else Filter.identity[http.Request, http.Response]
    val retryingClient = retry andThen client
    Trace.letClear(retryingClient(req)).flatMap(Api.parse[L])
  }

  protected def restartWatches(
    labelSelector: Option[String] = None,
    fieldSelector: Option[String] = None
  ): Future[(Seq[W], Option[String])] =
    get(labelSelector, fieldSelector, None, true).map { list =>
      (list.items.map(od.toWatch), list.metadata.flatMap(_.resourceVersion))
    }
}

/**
 * Namespaced list resources support more operations than non-namespaced, including POST, DELETE, and
 * retrieval of individual items in the list. (We don't have a full implementation yet).
 */
private[k8s] class NsListResource[O <: KubeObject: TypeReference, W <: Watch[O]: TypeReference, L <: KubeList[O]: TypeReference](
  client: Client,
  basePath: String,
  backoffs: Stream[Duration] = Watchable.DefaultBackoff,
  stats: StatsReceiver = DefaultStatsReceiver
)(implicit od: ObjectDescriptor[O, W]) extends ListResource[O, W, L](client, basePath, backoffs, stats) {
  // TODO: add watched method?
  def named(name: String): NsObjectResource[O, W] =
    new NsObjectResource[O, W](client, path, name)

  /**
   * Creates an Object within the represented List via an HTTP POST.
   *
   * @param toCreate the object to POST to the Kubernetes API.
   * @return if successful, the final created object (will include server-generated attributes like resourceVersion, etc).
   */
  def post(toCreate: O): Future[O] = {
    val req = Api.mkreq(http.Method.Post, path, Some(Json.writeBuf[O](toCreate)))
    Trace.letClear(client(req)).flatMap { rsp =>
      Api.parse[O](rsp)
    }
  }
}

private[k8s] class NsObjectResource[O <: KubeObject: TypeReference, W <: Watch[O]: TypeReference](
  val client: Client,
  listPath: String,
  objectName: String,
  protected val backoffs: Stream[Duration] = Watchable.DefaultBackoff,
  protected val stats: StatsReceiver = DefaultStatsReceiver,
  protected val nonWatchListPath: Option[String] = None
)(implicit od: ObjectDescriptor[O, W]) extends Watchable[O, W] with Resource {
  val path = s"$listPath/$objectName"
  val nonWatchPath = s"${nonWatchListPath.getOrElse(listPath)}/$objectName"

  def get(reqPath: String = path): Future[O] = {
    val req = Api.mkreq(http.Method.Get, reqPath, None)
    Trace.letClear(client(req)).flatMap { rsp =>
      Api.parse[O](rsp)
    }
  }

  def put(obj: O): Future[O] = {
    val req = Api.mkreq(http.Method.Put, path, Some(Json.writeBuf[O](obj)))
    Trace.letClear(client(req)).flatMap { rsp =>
      Api.parse[O](rsp)
    }
  }

  def delete: Future[O] = {
    val req = Api.mkreq(http.Method.Delete, path, None)
    Trace.letClear(client(req)).flatMap { rsp =>
      Api.parse[O](rsp)
    }
  }

  protected def restartWatches(
    labelSelector: Option[String] = None,
    fieldSelector: Option[String] = None
  ): Future[(Seq[W], Option[String])] =
    get(nonWatchPath).map { configMap =>
      (Seq(od.toWatch(configMap)), None)
    }
}
