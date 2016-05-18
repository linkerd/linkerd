package io.buoyant.namerd.storage.kubernetes

import com.fasterxml.jackson.annotation.JsonProperty
import io.buoyant.k8s.{KubeList, ObjectMeta}

case class DtabList(
  @JsonProperty("items") items: Seq[Dtab],
  kind: Option[String] = None,
  metadata: Option[ObjectMeta] = None,
  apiVersion: Option[String] = None
) extends KubeList[Dtab]
