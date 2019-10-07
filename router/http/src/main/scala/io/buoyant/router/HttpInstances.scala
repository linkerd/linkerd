package io.buoyant.router

import com.twitter.finagle.http.{HeaderMap, Request}
import io.buoyant.router.http.{HeadersLike, RequestLike}

object HttpInstances {

  implicit object HttpHeadersLike extends HeadersLike[HeaderMap] {
    override def toSeq(headers: HeaderMap): Seq[(String, String)] = headers.toSeq

    override def contains(headers: HeaderMap, k: String): Boolean = headers.contains(k)

    override def get(headers: HeaderMap, k: String): Option[String] = headers.get(k)

    override def getAll(headers: HeaderMap, k: String): Seq[String] = headers.getAll(k)

    override def add(headers: HeaderMap, k: String, v: String): Unit = {
      headers.add(k, v)
      ()
    }

    override def set(headers: HeaderMap, k: String, v: String): Unit = {
      headers.set(k, v)
      ()
    }

    override def remove(headers: HeaderMap, key: String): Seq[String] = {
      val r = getAll(headers, key)
      headers -= key
      r
    }

    override def iterator(headers: HeaderMap): Iterator[(String, String)] = headers.iterator
  }

  implicit object HttpRequestLike extends RequestLike[Request, HeaderMap] {
    override def headers(request: Request): HeaderMap = request.headerMap
  }
}
