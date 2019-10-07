package io.buoyant.router.http

/**
 * Type class for HTTP headers object
 * @tparam H Headers object type
 */
trait HeadersLike[H] {
  def toSeq(headers: H): Seq[(String, String)]
  def contains(headers: H, k: String): Boolean
  def get(headers: H, k: String): Option[String]
  def getAll(headers: H, k: String): Seq[String]
  def add(headers: H, k: String, v: String): Unit
  def set(headers: H, k: String, v: String): Unit
  def remove(headers: H, key: String): Seq[String]
  def iterator(headers: H): Iterator[(String, String)]
}
