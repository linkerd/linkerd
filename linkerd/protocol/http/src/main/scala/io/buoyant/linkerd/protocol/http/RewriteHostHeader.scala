package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.client.AddrMetadataExtraction.AddrMetadata
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.{Service, ServiceFactory, SimpleFilter, Stack}
import io.buoyant.namer.Metadata

object RewriteHostHeader {

  case class Filter(host: String) extends SimpleFilter[Request, Response] {
    def apply(req: Request, svc: Service[Request, Response]) = {
      // TODO: switch between Host/:authority for HTTP1.1/2.0 when Finagle will support it
      req.host = host
      svc(req)
    }
  }

  object module extends Stack.Module1[AddrMetadata, ServiceFactory[Request, Response]] {
    val role = Stack.Role("RewriteHostHeader")
    val description = "Rewrite request Host header according to concrete name metadata"

    def make(param: AddrMetadata, next: ServiceFactory[Request, Response]) = {
      param.metadata.get(Metadata.authority) match {
        case Some(host: String) => Filter(host) andThen next
        case _ => next
      }
    }
  }
}
