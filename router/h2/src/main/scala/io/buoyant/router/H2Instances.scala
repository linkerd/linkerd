package io.buoyant.router

import com.twitter.finagle.buoyant.h2.{Headers, Request}
import io.buoyant.router.http.{HeadersLike, RequestLike}

object H2Instances {

  implicit object H2HeadersLike extends HeadersLike[Headers] {
    override def toSeq(headers: Headers): Seq[(String, String)] = headers.toSeq

    override def contains(headers: Headers, k: String): Boolean = headers.contains(k)

    override def get(headers: Headers, k: String): Option[String] = headers.get(k)

    override def getAll(headers: Headers, k: String): Seq[String] = headers.getAll(k)

    override def add(headers: Headers, k: String, v: String): Unit = headers.add(k, v)

    override def set(headers: Headers, k: String, v: String): Unit = headers.set(k, v)

    override def remove(headers: Headers, key: String): Seq[String] = headers.remove(key)

    override def iterator(headers: Headers): Iterator[(String, String)] = headers.toSeq.iterator
  }

  implicit object H2RequestLike extends RequestLike[Request, Headers] {
    override def headers(request: Request): Headers = request.headers
  }
}
