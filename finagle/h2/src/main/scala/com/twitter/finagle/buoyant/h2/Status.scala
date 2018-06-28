package com.twitter.finagle.buoyant.h2

/*
 * Modified from com.twitter.finagle.http.Status.
 */

case class Status(code: Int) {
  override def toString = code.toString
}

object Status {

  private[finagle] def inRange(lower: Int, upper: Int, s: Status): Option[Status] =
    if (lower <= s.code && s.code < upper) Some(s) else None

  val Continue = Status(100)
  val SwitchingProtocols = Status(101)
  val Processing = Status(102)
  object Informational {
    def unapply(status: Status): Option[Status] = inRange(100, 200, status)
  }

  val Ok = Status(200)
  val Created = Status(201)
  val Accepted = Status(202)
  val NonAuthoritativeInformation = Status(203)
  val NoContent = Status(204)
  val ResetContent = Status(205)
  val PartialContent = Status(206)
  val MultiStatus = Status(207)
  val Cowabunga = Status(222)
  object Successful {
    def unapply(status: Status): Option[Status] = inRange(200, 300, status)
  }

  val MultipleChoices = Status(300)
  val MovedPermanently = Status(301)
  val Found = Status(302)
  val SeeOther = Status(303)
  val NotModified = Status(304)
  val UseProxy = Status(305)
  val TemporaryRedirect = Status(307)
  object Redirection {
    def unapply(status: Status): Option[Status] = inRange(300, 400, status)
  }

  val BadRequest = Status(400)
  val Unauthorized = Status(401)
  val PaymentRequired = Status(402)
  val Forbidden = Status(403)
  val NotFound = Status(404)
  val MethodNotAllowed = Status(405)
  val NotAcceptable = Status(406)
  val ProxyAuthenticationRequired = Status(407)
  val RequestTimeout = Status(408)
  val Conflict = Status(409)
  val Gone = Status(410)
  val LengthRequired = Status(411)
  val PreconditionFailed = Status(412)
  val RequestEntityTooLarge = Status(413)
  val RequestURITooLong = Status(414)
  val UnsupportedMediaType = Status(415)
  val RequestedRangeNotSatisfiable = Status(416)
  val ExpectationFailed = Status(417)
  val EnhanceYourCalm = Status(420)
  val UnprocessableEntity = Status(422)
  val Locked = Status(423)
  val FailedDependency = Status(424)
  val UnorderedCollection = Status(425)
  val UpgradeRequired = Status(426)
  val PreconditionRequired = Status(428)
  val TooManyRequests = Status(429)
  val RequestHeaderFieldsTooLarge = Status(431)
  val UnavailableForLegalReasons = Status(451)
  val ClientClosedRequest = Status(499)
  object ClientError {
    def unapply(status: Status): Option[Status] = inRange(400, 500, status)
  }

  val InternalServerError = Status(500)
  val NotImplemented = Status(501)
  val BadGateway = Status(502)
  val ServiceUnavailable = Status(503)
  val GatewayTimeout = Status(504)
  val HttpVersionNotSupported = Status(505)
  val VariantAlsoNegotiates = Status(506)
  val InsufficientStorage = Status(507)
  val NotExtended = Status(510)
  val NetworkAuthenticationRequired = Status(511)
  val EatMyShorts = Status(555)
  object ServerError {
    def unapply(status: Status): Option[Status] = inRange(500, 600, status)
  }

  def fromCode(code: Int): Status =
    statuses.getOrElse(code, Status(code))

  private[this] val statuses: Map[Int, Status] = Map(
    100 -> Continue,
    101 -> SwitchingProtocols,
    102 -> Processing,
    200 -> Ok,
    201 -> Created,
    202 -> Accepted,
    203 -> NonAuthoritativeInformation,
    204 -> NoContent,
    205 -> ResetContent,
    206 -> PartialContent,
    207 -> MultiStatus,
    222 -> Cowabunga,
    300 -> MultipleChoices,
    301 -> MovedPermanently,
    302 -> Found,
    303 -> SeeOther,
    304 -> NotModified,
    305 -> UseProxy,
    307 -> TemporaryRedirect,
    400 -> BadRequest,
    401 -> Unauthorized,
    402 -> PaymentRequired,
    403 -> Forbidden,
    404 -> NotFound,
    405 -> MethodNotAllowed,
    406 -> NotAcceptable,
    407 -> ProxyAuthenticationRequired,
    408 -> RequestTimeout,
    409 -> Conflict,
    410 -> Gone,
    411 -> LengthRequired,
    412 -> PreconditionFailed,
    413 -> RequestEntityTooLarge,
    414 -> RequestURITooLong,
    415 -> UnsupportedMediaType,
    416 -> RequestedRangeNotSatisfiable,
    417 -> ExpectationFailed,
    420 -> EnhanceYourCalm,
    422 -> UnprocessableEntity,
    423 -> Locked,
    424 -> FailedDependency,
    425 -> UnorderedCollection,
    426 -> UpgradeRequired,
    428 -> PreconditionRequired,
    429 -> TooManyRequests,
    431 -> RequestHeaderFieldsTooLarge,
    451 -> UnavailableForLegalReasons,
    499 -> ClientClosedRequest,
    500 -> InternalServerError,
    501 -> NotImplemented,
    502 -> BadGateway,
    503 -> ServiceUnavailable,
    504 -> GatewayTimeout,
    505 -> HttpVersionNotSupported,
    506 -> VariantAlsoNegotiates,
    507 -> InsufficientStorage,
    510 -> NotExtended,
    511 -> NetworkAuthenticationRequired,
    555 -> EatMyShorts
  )

}
