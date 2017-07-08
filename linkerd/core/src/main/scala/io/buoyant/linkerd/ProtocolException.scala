package io.buoyant.linkerd

/**
 * An exception indicating an error in protocol compliance
 * @param reason a message describing the cause of the error
 */
abstract class ProtocolException(reason: String) extends Exception(reason)
