package io.buoyant.namerd.storage.kubernetes

import io.buoyant.k8s.{Client, NsThirdPartyVersion, ThirdPartyVersion}

case class Api(client: Client) extends ThirdPartyVersion[Dtab] {
  override def owner: String = Api.Owner

  override def ownerVersion: String = Api.OwnerVersion

  override def withNamespace(ns: String) = new NsApi(client, ns)
  implicit val descriptor = DtabDescriptor
  def dtabs = listResource[Dtab, DtabWatch, DtabList]()
}

object Api {
  val Owner = "l5d.io"
  val OwnerVersion = "v1alpha1"
}

class NsApi(client: Client, ns: String)
  extends NsThirdPartyVersion[Dtab](client, Api.Owner, Api.OwnerVersion, ns) {
  implicit val descriptor = DtabDescriptor
  def dtabs = listResource[Dtab, DtabWatch, DtabList]()
}
