package io.buoyant.linkerd

/**
 * An exception indicating an error in protocol compliance
 * @param reason a message describing the cause of the error
 * @param protocol the name of the protocol in question
 */
// TODO: do we want to consider adding a proto version marker to
//       this exception?
abstract class ProtocolException(reason: String, protocol: String)
  extends Exception(s"$protocol: $reason")
