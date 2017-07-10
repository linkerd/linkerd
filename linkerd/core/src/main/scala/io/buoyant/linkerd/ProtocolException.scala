package io.buoyant.linkerd

import scala.util.control.NoStackTrace

/**
 * An exception indicating an error in protocol compliance
 *
 * @param reason a message describing the cause of the error
 */
abstract class ProtocolException(reason: String)
  extends Exception(reason)
  with NoStackTrace
