package io.buoyant.namer

import com.twitter.finagle.addr.WeightedAddress

object Metadata {
  val authority = "authority" // HTTP/1.1 Host or HTTP/2.0 :authority
  val nodeName = "nodeName"
  val endpointWeight = WeightedAddress.weightKey
  val serviceMeta = "service_meta"
  val nodeMeta = "node_meta"
}
