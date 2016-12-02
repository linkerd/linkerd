package com.twitter.finagle.buoyant.h2
package netty4

/**
 * Detect connection-headers in accordance with RFC 7540 §8.1.2.2:
 *
 *     HTTP/2 does not use the Connection header field to indicate
 *     connection-specific header fields; in this protocol,
 *     connection-specific metadata is conveyed by other means. An
 *     endpoint MUST NOT generate an HTTP/2 message containing
 *     connection-specific header fields; any message containing
 *     connection-specific header fields MUST be treated as malformed
 *     (Section 8.1.2.6).
 *
 *     The only exception to this is the TE header field, which MAY be
 *     present in an HTTP/2 request; when it is, it MUST NOT contain
 *     any value other than "trailers".
 *
 *     This means that an intermediary transforming an HTTP/1.x
 *     message to HTTP/2 will need to remove any header fields
 *     nominated by the Connection header field, along with the
 *     Connection header field itself. Such intermediaries SHOULD also
 *     remove other connection-specific header fields, such as
 *     Keep-Alive, Proxy-Connection, Transfer-Encoding, and Upgrade,
 *     even if they are not nominated by the Connection header field.
 */
object ConnectionHeaders {
  val Connection = "connection"
  val KeepAlive = "keep-alive"
  val ProxyAuthenticate = "proxy-authenticate"
  val ProxyAuthorization = "proxy-authorzation"
  val ProxyConnection = "proxy-connection"
  val Te = "te"
  val Trailer = "trailer"
  val TransferEncoding = "transfer-encoding"
  val Upgrade = "upgrade"

  private val Trailers = Seq("trailers")

  /**
   * Returns true iff Headers has connection-specific headers (and
   * should be considered malformed).
   */
  def detect(headers: Headers): Boolean = {
    headers.contains(Connection) ||
      headers.contains(KeepAlive) ||
      headers.contains(ProxyAuthenticate) ||
      headers.contains(ProxyAuthorization) ||
      headers.contains(ProxyConnection) ||
      headers.contains(Trailer) ||
      headers.contains(TransferEncoding) ||
      headers.contains(Upgrade) ||
      detectTE(headers)
  }

  private def detectTE(headers: Headers): Boolean =
    headers.contains(Te) && (headers.get(Te) != Trailers)
}
