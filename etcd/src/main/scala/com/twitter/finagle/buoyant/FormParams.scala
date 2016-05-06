package com.twitter.finagle.buoyant

import com.twitter.finagle.http.Request
import org.jboss.netty.handler.codec.http.multipart._
import scala.collection.JavaConverters._

/**
 * Finagle's request type doesn't expose any way to interact with form
 * parameters.  Netty provides utilities for this, but the underlying
 * netty message types can't be accessed from outside of the
 * finagle. SO, we just shove this into the finagle package so that we
 * can do this.  This should be fed back upstream and then removed.
 */
object FormParams {
  type Params = Map[String, Seq[String]]

  def set(req: Request, params: Seq[(String, String)]): Unit = {
    val enc = new HttpPostRequestEncoder(req.httpRequest, false)
    for ((k, v) <- params) {
      enc.addBodyAttribute(k, v)
    }
    val _ = enc.finalizeRequest()
  }

  def get(req: Request): Params = {
    val dec = new HttpPostRequestDecoder(req.httpRequest)
    dec.getBodyHttpDatas().asScala.foldLeft[Params](Map.empty) {
      case (attrs, attr: Attribute) =>
        val k = attr.getName
        val v = attr.getValue
        val vals = attrs.getOrElse(k, Seq.empty) :+ v
        attrs + (k -> vals)

      case (attrs, _) => attrs
    }
  }

}
