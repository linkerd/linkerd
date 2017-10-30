package io.buoyant.namerd.storage.kubernetes

import com.fasterxml.jackson.core.`type`.TypeReference
import io.buoyant.k8s.{Client, NsCustomResourceVersion, ResourceVersionOrdering, CustomResourceVersion}

case class Api(client: Client) extends CustomResourceVersion[Dtab] {
  override def owner: String = Api.Owner

  override def ownerVersion: String = Api.OwnerVersion

  override def withNamespace(ns: String) = new NsApi(client, ns)
  implicit private[this] val descriptor = DtabDescriptor
  implicit private[this] val dtabTypeRef = new TypeReference[Dtab] {}
  implicit private[this] val dtabWatchTypeRef = new TypeReference[DtabWatch] {}
  implicit private[this] val dtabListTypeRef = new TypeReference[DtabList] {}
  implicit private[this] val dtabWatchIsVersioned = new ResourceVersionOrdering[Dtab, DtabWatch]
  def dtabs = listResource[Dtab, DtabWatch, DtabList]()
}

object Api {
  val Owner = "l5d.io"
  val OwnerVersion = "v1alpha1"
}

class NsApi(client: Client, ns: String)
  extends NsCustomResourceVersion[Dtab](client, Api.Owner, Api.OwnerVersion, ns) {
  implicit private[this] val descriptor = DtabDescriptor
  implicit private[this] val dtabTypeRef = new TypeReference[Dtab] {}
  implicit private[this] val dtabWatchTypeRef = new TypeReference[DtabWatch] {}
  implicit private[this] val dtabListTypeRef = new TypeReference[DtabList] {}
  implicit private[this] val dtabWatchIsVersioned = new ResourceVersionOrdering[Dtab, DtabWatch]
  def dtabs = listResource[Dtab, DtabWatch, DtabList]()
}
