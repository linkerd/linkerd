package io.buoyant.consul.v1

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.finagle.{Service, http}
import com.twitter.finagle.http.{Request, Response, Status, Version}
import com.twitter.finagle.Backoff
import com.twitter.finagle.stats.{DefaultStatsReceiver, StatsReceiver}
import com.twitter.io.{Buf, Reader}
import com.twitter.util.Future
import io.buoyant.test.Awaits
import org.scalatest.FunSuite
import com.twitter.conversions.DurationOps._


class BaseApiTest extends FunSuite with Awaits {
  import BaseApiTest._

  test("works with chunked response") {
    val service = stubService(chunked = true)
    val result = await(StubApi(service).result())
    assert(result == ChunkedStubData)
  }

  test("works with non-chunked response") {
    val service = stubService(chunked = false)
    val result = await(StubApi(service).result())
    assert(result == NonChunkedStubData)
  }

}

object BaseApiTest {

  private val mapper = new ObjectMapper with ScalaObjectMapper
  mapper.registerModule(DefaultScalaModule)

  final case class StubData(dataType: String)

  val ChunkedStubData = StubData("chunked")
  val NonChunkedStubData = StubData("non-chunked")
  val ChunkSize = 1

  def stubService(chunked: Boolean) = Service.mk[Request, Response] { _ =>
    val rsp =
      if (chunked)
        Response(Version.Http11, Status.Ok, Reader.fromBuf(Buf.Utf8(mapper.writeValueAsString(ChunkedStubData)), ChunkSize))
      else Response()
    rsp.setContentTypeJson()
    if (!chunked)
      rsp.content = Buf.Utf8(mapper.writeValueAsString(NonChunkedStubData))
    rsp.headerMap.set("X-Consul-Index", "4")
    Future.value(rsp)
  }

  final case class StubApi(client: Client) extends BaseApi {
    override val uriPrefix: String = s"/$versionString"
    override val backoffs: Backoff = Backoff.exponentialJittered(1.milliseconds, 5.seconds)

    override def stats: StatsReceiver = DefaultStatsReceiver

    def result(retry: Boolean = false): Future[StubData] = {
      val req = mkreq(http.Method.Get, s"$uriPrefix/test", None)
      executeJson[StubData](req, retry).map(_.value)
    }

  }

}
