package io.buoyant.namer.marathon

import com.fasterxml.jackson.core.JsonParseException
import com.twitter.finagle.{Failure, Service, StackParams}
import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.io.Buf
import com.twitter.util.Future
import io.buoyant.marathon.v2.Api
import io.buoyant.test.{Awaits, Exceptions}
import org.scalatest.FunSuite

class AuthenticatorTest extends FunSuite with Awaits with Exceptions {

  val privateKeyRSA = """-----BEGIN RSA PRIVATE KEY-----
MIIEpQIBAAKCAQEAvzoCEC2rpSpJQaWZbUmlsDNwp83Jr4fi6KmBWIwnj1MZ6CUQ
7rBasuLI8AcfX5/10scSfQNCsTLV2tMKQaHuvyrVfwY0dINk+nkqB74QcT2oCCH9
XduJjDuwWA4xLqAKuF96FsIes52opEM50W7/W7DZCKXkC8fFPFj6QF5ZzApDw2Qs
u3yMRmr7/W9uWeaTwfPx24YdY7Ah+fdLy3KN40vXv9c4xiSafVvnx9BwYL7H1Q8N
iK9LGEN6+JSWfgckQCs6UUBOXSZdreNN9zbQCwyzee7bOJqXUDAuLcFARzPw1EsZ
AyjVtGCKIQ0/btqK+jFunT2NBC8RItanDZpptQIDAQABAoIBAQCsssO4Pra8hFMC
gX7tr0x+tAYy1ewmpW8stiDFilYT33YPLKJ9HjHbSms0MwqHftwwTm8JDc/GXmW6
qUui+I64gQOtIzpuW1fvyUtHEMSisI83QRMkF6fCSQm6jJ6oQAtOdZO6R/gYOPNb
3gayeS8PbMilQcSRSwp6tNTVGyC33p43uUUKAKHnpvAwUSc61aVOtw2wkD062XzM
hJjYpHm65i4V31AzXo8HF42NrAtZ8K/AuQZne5F/6F4QFVlMKzUoHkSUnTp60XZx
X77GuyDeDmCgSc2J7xvR5o6VpjsHMo3ek0gJk5ZBnTgkHvnpbULCRxTmDfjeVPue
v3NN2TBFAoGBAPxbqNEsXPOckGTvG3tUOAAkrK1hfW3TwvrW/7YXg1/6aNV4sklc
vqn/40kCK0v9xJIv9FM/l0Nq+CMWcrb4sjLeGwHAa8ASfk6hKHbeiTFamA6FBkvQ
//7GP5khD+y62RlWi9PmwJY21lEkn2mP99THxqvZjQiAVNiqlYdwiIc7AoGBAMH8
f2Ay7Egc2KYRYU2qwa5E/Cljn/9sdvUnWM+gOzUXpc5sBi+/SUUQT8y/rY4AUVW6
YaK7chG9YokZQq7ZwTCsYxTfxHK2pnG/tXjOxLFQKBwppQfJcFSRLbw0lMbQoZBk
S+zb0ufZzxc2fJfXE+XeJxmKs0TS9ltQuJiSqCPPAoGBALEc84K7DBG+FGmCl1sb
ZKJVGwwknA90zCeYtadrIT0/VkxchWSPvxE5Ep+u8gxHcqrXFTdILjWW4chefOyF
5ytkTrgQAI+xawxsdyXWUZtd5dJq8lxLtx9srD4gwjh3et8ZqtFx5kCHBCu29Fr2
PA4OmBUMfrs0tlfKgV+pT2j5AoGBAKnA0Z5XMZlxVM0OTH3wvYhI6fk2Kx8TxY2G
nxsh9m3hgcD/mvJRjEaZnZto6PFoqcRBU4taSNnpRr7+kfH8sCht0k7D+l8AIutL
ffx3xHv9zvvGHZqQ1nHKkaEuyjqo+5kli6N8QjWNzsFbdvBQ0CLJoqGhVHsXuWnz
W3Z4cBbVAoGAEtnwY1OJM7+R2u1CW0tTjqDlYU2hUNa9t1AbhyGdI2arYp+p+umA
b5VoYLNsdvZhqjVFTrYNEuhTJFYCF7jAiZLYvYm0C99BqcJnJPl7JjWynoNHNKw3
9f6PIOE1rAmPE8Cfz/GFF5115ZKVlq+2BY8EKNxbCIy2d/vMEvisnXI=
-----END RSA PRIVATE KEY-----"""


  val loginPath = "/fake_login_endpoint"
  val loginEndpoint =s"http://example.com$loginPath"
  val authRequest = Authenticator.AuthRequest(loginPath, "fakeuid", privateKeyRSA)
  val authbuf = Buf.Utf8("""{"token":"foo"}""")
  val concurrencyLoad = 100

  case class stubService(
    val authStatus: Status,
    authContent: Buf,
    val reqStatus: Status = Status.Ok
  ) {
    @volatile var nextAuthStatus = authStatus
    @volatile var nextReqStatus = reqStatus

    @volatile var auths = 0
    @volatile var requests = 0

    def service() =
      Service.mk[Request, Response] { req =>
        val rsp =
          if (req.path == loginPath) {
            assert(req.contentString == authRequest.jwt)
            auths += 1
            val r = Response(nextAuthStatus)
            nextAuthStatus = authStatus
            r.content = authContent
            r
          } else {
            requests += 1
            val r = Response(nextReqStatus)
            nextReqStatus = reqStatus
            r
          }

        Future.value(rsp)
      }
  }

  test("auths on first request only") {
    val stub = stubService(Status.Ok, authbuf)
    val auth = new Authenticator.Authenticated(stub.service(), authRequest)

    val rsp1 = await(auth(Request()))
    assert(rsp1.status == Status.Ok)
    assert(stub.auths == 1)
    assert(stub.requests == 1)

    val rsp2 = await(auth(Request()))
    assert(rsp2.status == Status.Ok)
    assert(stub.auths == 1)
    assert(stub.requests == 2)
  }

  test("auths once when called repeatedly") {
    val stub = stubService(Status.Ok, authbuf)
    val auth = new Authenticator.Authenticated(stub.service(), authRequest)

    val responses = await(
      Future.collect(
        Seq.range(0, concurrencyLoad).map { _ =>
          auth(Request())
        }
      )
    )

    assert(responses.forall(_.status == Status.Ok))
    assert(stub.auths == 1)
    assert(stub.requests == concurrencyLoad)
  }

  test("retries auth on Unauthorized http response") {
    val stub = stubService(Status.Ok, authbuf)
    val auth = new Authenticator.Authenticated(stub.service(), authRequest)

    val rsp1 = await(auth(Request()))
    assert(rsp1.status == Status.Ok)
    assert(stub.auths == 1)
    assert(stub.requests == 1)

    stub.nextReqStatus = Status.Unauthorized

    val rsp2 = await(auth(Request()))
    assert(rsp2.status == Status.Ok)
    assert(stub.auths == 2)
    assert(stub.requests == 3)
  }

  test("fails on BadRequest http responses") {
    val stub = stubService(Status.Ok, authbuf, Status.BadRequest)
    val auth = new Authenticator.Authenticated(stub.service(), authRequest)

    val rsp1 = await(auth(Request()))
    assert(rsp1.status == Status.BadRequest)
    assert(stub.auths == 1)
    assert(stub.requests == 1)

    val rsp2 = await(auth(Request()))
    assert(rsp2.status == Status.BadRequest)
    assert(stub.auths == 1)
    assert(stub.requests == 2)
  }

  test("throws UnauthorizedResponse on subsequent Unauthorized auth failure") {
    val stub = stubService(Status.Ok, authbuf, Status.Ok)
    val auth = new Authenticator.Authenticated(stub.service(), authRequest)

    val rsp1 = await(auth(Request()))
    assert(rsp1.status == Status.Ok)
    assert(stub.auths == 1)
    assert(stub.requests == 1)

    stub.nextReqStatus = Status.Unauthorized
    stub.nextAuthStatus = Status.Unauthorized

    assertThrows[Authenticator.UnauthorizedResponse] {
      await(auth(Request()))
    }
    assert(stub.auths == 2)
    assert(stub.requests == 2)
  }

  test("retries and fails on repeated Unauthorized http responses") {
    val stub = stubService(Status.Ok, authbuf, Status.Unauthorized)
    val auth = new Authenticator.Authenticated(stub.service(), authRequest)

    assertThrows[Authenticator.UnauthorizedResponse] {
      await(auth(Request()))
    }
    assert(stub.auths == 1)
    assert(stub.requests == 1)

    assertThrows[Authenticator.UnauthorizedResponse] {
      await(auth(Request()))
    }
    assert(stub.auths == 2)
    assert(stub.requests == 3)
  }

  test("throws UnexpectedResponse with BadRequest auth response") {
    val stub = stubService(Status.BadRequest, authbuf)
    val auth = new Authenticator.Authenticated(stub.service(), authRequest)

    assertThrows[Api.UnexpectedResponse] {
      await(auth(Request()))
    }

    assert(stub.auths == 1)
    assert(stub.requests == 0)
  }

  test("throws Failure with unexpected auth response") {
    val stub = stubService(Status.Ok, Buf.Utf8("""{"unexpected":"foo"}"""))
    val auth = new Authenticator.Authenticated(stub.service(), authRequest)

    assertThrows[Failure] {
      await(auth(Request()))
    }

    assert(stub.auths == 1)
    assert(stub.requests == 0)
  }

  test("throws JsonParseException with bad auth response") {
    val stub = stubService(Status.Ok, Buf.Utf8("bad auth"))
    val auth = new Authenticator.Authenticated(stub.service(), authRequest)

    assertThrows[JsonParseException] {
      await(auth(Request()))
    }

    assert(stub.auths == 1)
    assert(stub.requests == 0)
  }

  test("Authenticated creation fails on bad loginEndpoint") {
    assertThrows[java.net.URISyntaxException] {
      val secret = MarathonSecret(Some("bad endpoint"),Some(privateKeyRSA), Some("RS256"), Some("fakeuid") )
      MarathonSecret.mkAuthenticated(secret, StackParams.empty)

    }
  }

  test("AuthRequest fails on bad privateKey") {
    assertThrows[java.lang.IllegalArgumentException] {
      Authenticator.AuthRequest(s"http://example.com$loginEndpoint", "uid", "bad private key")
    }
  }
}
