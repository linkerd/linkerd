package io.buoyant.linkerd

import io.buoyant.linkerd.config.types.Port

case class Admin(port: Port = Port(9990))
