package io.buoyant.linkerd.protocol

case class FramingException(reason: String) extends Exception(reason)