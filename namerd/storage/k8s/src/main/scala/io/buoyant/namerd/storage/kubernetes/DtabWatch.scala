package io.buoyant.namerd.storage.kubernetes

import com.fasterxml.jackson.annotation.{JsonProperty, JsonSubTypes, JsonTypeInfo}
import io.buoyant.k8s.{Status, Watch}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(Array(
  new JsonSubTypes.Type(value = classOf[DtabAdded], name = "ADDED"),
  new JsonSubTypes.Type(value = classOf[DtabModified], name = "MODIFIED"),
  new JsonSubTypes.Type(value = classOf[DtabDeleted], name = "DELETED"),
  new JsonSubTypes.Type(value = classOf[DtabError], name = "ERROR")
))
sealed trait DtabWatch extends Watch[Dtab]

// NOTE: These are not (as would be usual practice) inside a DtabWatch companion object,
// because doing so leads to intermittent compilation failures from
// https://issues.scala-lang.org/browse/SI-7551.
case class DtabAdded(`object`: Dtab) extends DtabWatch with Watch.Added[Dtab]
case class DtabModified(`object`: Dtab) extends DtabWatch with Watch.Modified[Dtab]
case class DtabDeleted(`object`: Dtab) extends DtabWatch with Watch.Deleted[Dtab]
case class DtabError(
  @JsonProperty(value = "object") status: Status
) extends DtabWatch with Watch.Error[Dtab]
