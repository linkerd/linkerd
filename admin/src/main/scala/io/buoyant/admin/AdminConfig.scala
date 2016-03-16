package io.buoyant.admin

import io.buoyant.config.types.Port

case class AdminConfig(port: Port = Port(9990))
