package io.buoyant.router.http

import com.twitter.finagle.Service
import com.twitter.finagle.http.Fields.Via
import com.twitter.finagle.http.{HeaderMap, Request, Response, Status}
import com.twitter.util.Future
import io.buoyant.test.{Awaits, FunSuite}


class MaxCallDepthFilterTest extends FunSuite with Awaits {

  implicit object HttpHeadersLike extends HeadersLike[HeaderMap] {
    override def toSeq(headers: HeaderMap): Seq[(String, String)] = ???
    override def contains(headers: HeaderMap, k: String): Boolean = ???
    override def get(headers: HeaderMap, k: String): Option[String] = headers.get(k)
    override def getAll(headers: HeaderMap, k: String): Seq[String] = ???
    override def add(headers: HeaderMap, k: String, v: String): Unit = ???
    override def set(headers: HeaderMap, k: String, v: String): Unit = ???
    override def remove(headers: HeaderMap, key: String): Seq[String] = ???
    override def iterator(headers: HeaderMap): Iterator[(String, String)] = ???
  }

  implicit object HttpRequestLike extends RequestLike[Request, HeaderMap] {
    override def headers(request: Request): HeaderMap = request.headerMap
  }


  def service(maxCallDepth: Int) = new MaxCallDepthFilter[Request, HeaderMap, Response](
    maxCallDepth,
    Via
  ).andThen(Service.mk[Request, Response](_ => Future.value(Response())))

  test("passes through requests not exceeding max hops") {
    val viaHeader = (1 to 10).map(v => s"hop $v").mkString(", ")
    val req = Request()
    req.headerMap.add(Via, viaHeader)
    assert(await(service(10)(req)).status == Status.Ok)
  }

  test("stops requests exceeding max hops") {
    val expectedMessage = "Maximum number of calls (9) has been exceeded. Please check for proxy loops."
    val viaHeader = (1 to 10).map(v => s"hop $v").mkString(", ")
    val req = Request()
    req.headerMap.add(Via, viaHeader)

    val exception = intercept[MaxCallDepthFilter.MaxCallDepthExceeded] {
      await(service(9)(req))
    }
    assert(exception.getMessage == expectedMessage)
  }


}
