package io.buoyant.linkerd.config

import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "protocol")
trait RouterConfig {
  var label: Option[String] = None
  var baseDtab: Option[String] = None
  var dstPrefix: Option[String] = None
  var failFast: Boolean = false
  var timeoutMs: Option[Int] = None
}

