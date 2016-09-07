package io.buoyant.router.http

import com.twitter.finagle.http.Request
import scala.util.control.NoStackTrace

case class UnidentifiableRequestException(req: Request, reason: String)
  extends Exception(s"Could not identify $req because $reason")
  with NoStackTrace
