package io.buoyant.namer.marathon

import com.twitter.finagle.{Failure, FailureFlags, http}
import com.twitter.logging.Logger
import com.twitter.util.{Future, Promise, Return, Throw, Time}
import io.buoyant.marathon.v2.Api
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import pdi.jwt.{Jwt, JwtAlgorithm}
import scala.annotation.tailrec

object Authenticator {

  case class AuthRequest(
    loginEndpoint: String,
    uid: String,
    privateKey: String,
    algorithm: JwtAlgorithm = JwtAlgorithm.RS256
  ) {
    val path: String = new URL(loginEndpoint).getPath
    val jwt: String = {
      val token = Jwt.encode(s"""{"uid":"$uid"}""", privateKey, algorithm)
      s"""{"uid":"$uid","token":"$token"}"""
    }
  }
  private[marathon] case class UnauthorizedResponse(rsp: http.Response) extends Throwable
  private[this] case class AuthToken(token: Option[String])
  private[this] val closedException = Failure("closed", FailureFlags.Interrupted)
  private[this] val closedExceptionF = Future.exception(closedException)
  private[this] val missingTokenException = Failure("missing token")
  private[this] val missingTokenExceptionF = Future.exception(missingTokenException)

  class Authenticated(client: Api.Client, authRequest: AuthRequest) extends Api.Client {

    private[this] val log = Logger.get(getClass.getName)
    private[this] sealed trait State
    private[this] object Init extends State
    private[this] case class Authenticating(token: Future[String]) extends State
    private[this] object Closed extends State
    private[this] val state = new AtomicReference[State](Init)

    final def apply(req: http.Request): Future[http.Response] = state.get match {
      case Init =>
        val tokenP = new Promise[String]
        if (state.compareAndSet(Init, Authenticating(tokenP))) {
          tokenP.become(getToken())
          tokenP.flatMap(issueAuthed(req, _))
        } else apply(req) // try again on race

      case s0@Authenticating(tokenF) =>
        tokenF.flatMap(issueAuthed(req, _)).rescue {
          case UnauthorizedResponse(rsp) =>
            // If a request is unauthorized even though we have a
            // token, it may have expired. If another request hasn't
            // already started authenticating, get a new token and
            // reissue the request at most once.
            val tokenP = new Promise[String]
            if (state.compareAndSet(s0, Authenticating(tokenP))) {
              tokenP.become(getToken())
              tokenP.flatMap(issueAuthed(req, _))
            } else apply(req) // try again on race
          case e =>
            Future.exception(e)
        }

      case Closed => closedExceptionF
    }

    private[this] def issueAuthed(req: http.Request, token: String): Future[http.Response] = {
      log.debug(s"Issuing authenticated request to ${req.path}")
      req.headerMap.set("Authorization", s"token=$token")
      client(req).flatMap {
        case rsp if rsp.status == http.Status.Unauthorized =>
          Future.exception(UnauthorizedResponse(rsp))
        case rsp => Future.value(rsp)
      }
    }

    private[this] def getToken(): Future[String] = {
      val tokReq = http.Request(http.Method.Post, authRequest.path)
      tokReq.setContentTypeJson()
      tokReq.setContentString(authRequest.jwt)
      client(tokReq).flatMap {
        case rsp if rsp.status == http.Status.Ok =>
          log.debug(s"Got successful token response from ${authRequest.loginEndpoint}")
          Api.readJson[AuthToken](rsp.content) match {
            case Return(AuthToken(Some(token))) => Future.value(token)
            case Return(AuthToken(None)) => missingTokenExceptionF
            case Throw(e) => Future.exception(e)
          }
        case rsp if rsp.status == http.Status.Unauthorized =>
          log.debug(s"Got Unauthorized token response from ${authRequest.loginEndpoint}")
          Future.exception(UnauthorizedResponse(rsp))
        case rsp =>
          log.debug(s"Got unexpected token response from ${authRequest.loginEndpoint}. Status: ${rsp.statusCode}")
          Future.exception(Api.UnexpectedResponse(rsp))
      }.onFailure(log.error(_, "Obtaining a token failed"))
    }

    @tailrec final override def close(d: Time): Future[Unit] = {
      state.get match {
        case Init =>
          if (state.compareAndSet(Init, Closed)) {
            client.close(d)
          } else close(d)
        case s0@Authenticating(tokenF) =>
          if (state.compareAndSet(s0, Closed)) {
            tokenF.raise(closedException)
            client.close(d)
          } else close(d)
        case Closed => Future.Unit
      }
    }
  }

}
