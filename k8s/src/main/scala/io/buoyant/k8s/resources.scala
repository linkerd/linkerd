package io.buoyant.k8s

import com.fasterxml.jackson.core.`type`.TypeReference
import com.twitter.finagle.http
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.stats.{DefaultStatsReceiver, StatsReceiver}
import com.twitter.finagle.tracing.Trace
import com.twitter.util.TimeConversions._
import com.twitter.util.{Closable, _}

/**
 * Basic functionality shared by all k8s Resource implementations: they require an HTTP client,
 * and can be closed.
 */
trait Resource extends Closable {
  def client: Client
  def close(deadline: Time) = client.close(deadline)
  def path: String
  def watchPath: String
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

  override val path = s"/$group/$version"
  override val watchPath = s"$path/watch"

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

  def listResource[T <: O: TypeReference, W <: Watch[T]: TypeReference: Ordering, L <: KubeList[T]: TypeReference](
    backoffs: Stream[Duration] = Backoff.exponentialJittered(1.milliseconds, 5.seconds),
    stats: StatsReceiver = DefaultStatsReceiver
  )(implicit od: ObjectDescriptor[T, W]) = new ListResource[T, W, L](this, backoffs, stats)
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
  val ns: String
)
  extends Resource {
  override val path = s"/$group/$version/namespaces/$ns"
  override val watchPath = s"/$group/$version/watch/namespaces/$ns"

  def listResource[T <: O: TypeReference, W <: Watch[T]: Ordering: TypeReference, L <: KubeList[T]: TypeReference](
    backoffs: Stream[Duration] = Backoff.exponentialJittered(1.milliseconds, 5.seconds),
    stats: StatsReceiver = DefaultStatsReceiver
  )(implicit od: ObjectDescriptor[T, W]) =
    new NsListResource[T, W, L](this, backoffs, stats)

  def objectResource[T <: O: TypeReference, W <: Watch[T]: Ordering: TypeReference](
    name: String,
    backoffs: Stream[Duration] = Backoff.exponentialJittered(1.milliseconds, 5.seconds),
    stats: StatsReceiver = DefaultStatsReceiver
  )(implicit od: ObjectDescriptor[T, W]): NsObjectResource[T, W] = {
    val listName = implicitly[ObjectDescriptor[T, W]].listName
    new NsObjectResource[T, W](this, name, Some(listName), backoffs, stats)
  }
}

/**
  * A custom resource version contains CustomResource-typed objects. The API was called ThirdPartyResources in Kubernetes
  * < 1.7 and has been deprecated since version 1.8. The APIs for both resource versions are identical in implementation. However,
  * various naming standards have been changed to make the apis more meaningful. For me details
  * see https://kubernetes.io/docs/tasks/access-kubernetes-api/extend-api-third-party-resource/.
  * To understand how to create your own custom resources see
  * https://kubernetes.io/docs/tasks/access-kubernetes-api/extend-api-custom-resource-definitions/ for a description of the
  * design model.
  *
  * Implementors are likely to want to extend this to provide convenience methods on top of the `list` method.
  *
  * @tparam O The parent type for all [[KubeObject]]s served by this Version.
  */
trait CustomResourceVersion[O <: KubeObject] extends Version[O] {
  /** domain name, i.e. "buoyant.io" */
  def owner: String

  /** version within owner domain, i.e. "v1" */
  def ownerVersion: String

  override val version: String = CustomResourceVersion.version(owner, ownerVersion)
  override val path: String = s"/${CustomResourceVersion.group}/$version"
  override val watchPath: String = s"$path/watch"

  val group: String = CustomResourceVersion.group
  override def withNamespace(ns: String) = new NsCustomResourceVersion[O](client, owner, ownerVersion, ns)
}

object CustomResourceVersion {
  def version(owner: String, ownerVersion: String) = s"$owner/$ownerVersion"
  val group = "apis"
}

/**
 * A namespaced third-party version, i.e. "/apis/buoyant.io/v1/namespaces/ns/".
 */
class NsCustomResourceVersion[O <: KubeObject](client: Client, owner: String, ownerVersion: String, ns: String)
  extends NsVersion[O](client, CustomResourceVersion.group, CustomResourceVersion.version(owner, ownerVersion), ns)

/**
 * Represents the functionality for a  kubernetes API resource serving a list of objects, for example:
 * `/api/v1/namespaces/{namespace}/endpoints`.
 */
private[k8s] class ListResource[O <: KubeObject: TypeReference, W <: Watch[O]: Ordering: TypeReference, L <: KubeList[O]: TypeReference](
  parent: Resource,
  protected val backoffs: Stream[Duration] = Watchable.DefaultBackoff,
  protected val stats: StatsReceiver = DefaultStatsReceiver
)(implicit od: ObjectDescriptor[O, W])
  extends Watchable[O, W, L]
  with Resource {

  override val watchResourceVersion: Boolean = true
  override val client: Client = parent.client
  val name: String = implicitly[ObjectDescriptor[O, W]].listName
  final override val path = s"${parent.path}/$name"
  final override val watchPath = s"${parent.watchPath}/$name"
  /**
   * @return a Future containing a sequence of Watches and an optional String representing the current resourceVersion
   */
  override protected def restartWatches(
    labelSelector: Option[String],
    fieldSelector: Option[String]
  ): Future[(Seq[W], Option[String])] =
    get(
      labelSelector = labelSelector,
      fieldSelector = fieldSelector,
      retryIndefinitely = true
    ).map { maybeList =>
      val list = maybeList.get // list resources should always exist
      (list.items.map(od.toWatch), list.metadata.flatMap(_.resourceVersion))
    }
}

/**
 * Namespaced list resources support more operations than non-namespaced, including POST, DELETE, and
 * retrieval of individual items in the list. (We don't have a full implementation yet).
 */
private[k8s] class NsListResource[O <: KubeObject: TypeReference, W <: Watch[O]: Ordering: TypeReference, L <: KubeList[O]: TypeReference](
  parent: NsVersion[_],
  backoffs: Stream[Duration] = Watchable.DefaultBackoff,
  stats: StatsReceiver = DefaultStatsReceiver
)(implicit od: ObjectDescriptor[O, W]) extends ListResource[O, W, L](parent, backoffs, stats) {

  override val watchResourceVersion: Boolean = true
  val ns: String = parent.ns
  def named(objName: String): NsObjectResource[O, W] =
    new NsObjectResource[O, W](this, objName, None, backoffs, stats)

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

private[k8s] class NsObjectResource[O <: KubeObject: TypeReference, W <: Watch[O]: Ordering: TypeReference](
  parent: Resource,
  objectName: String,
  listName: Option[String] = None,
  protected val backoffs: Stream[Duration] = Watchable.DefaultBackoff,
  protected val stats: StatsReceiver = DefaultStatsReceiver
)(implicit od: ObjectDescriptor[O, W])
  extends Watchable[O, W, O]
  with Resource {
  override val watchResourceVersion: Boolean = false
  override val client: Client = parent.client
  override val (path, watchPath) = {
    val listNameOrDefault = listName.map(_ + "/").getOrElse("")
    (s"${parent.path}/$listNameOrDefault$objectName",
      s"${parent.watchPath}/$listNameOrDefault$objectName")
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

  override protected def restartWatches(
    labelSelector: Option[String] = None,
    fieldSelector: Option[String] = None
  ): Future[(Seq[W], Option[String])] =
    get(
      labelSelector = labelSelector,
      fieldSelector = fieldSelector,
      retryIndefinitely = true
    ).map { maybeObj =>
      val watch = maybeObj.toSeq.flatMap { obj => Seq(od.toWatch(obj)) }
      // object watches don't supply a resource version, since the version associated
      // with the object is tied to when the object was last modified, and therefore
      // it may be too old to successfully establish a watch
      (watch, None)
    }
}
