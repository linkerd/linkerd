package io.buoyant.k8s.istio

/**
 * Defines a request's metadata for istio rules to match against
 * (normalizes fields between http and h2)
 */
case class IstioRequest(
  uri: String,
  scheme: String,
  method: String,
  authority: String,
  getHeader: (String) => Option[String]
)
