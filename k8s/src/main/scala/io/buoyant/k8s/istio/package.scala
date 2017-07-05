package io.buoyant.k8s

package object istio {
  val DefaultDiscoveryHost = "istio-pilot"
  val DefaultDiscoveryPort = 8080

  val DefaultApiserverHost = "istio-pilot"
  val DefaultApiserverPort = 8081

  val DefaultMixerHost = "istio-mixer"
  val DefaultMixerPort = 9091
}
