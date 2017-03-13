package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.client.AddrMetadataExtraction.AddrMetadata
import com.twitter.finagle.http.{Fields, Request, Response}
import com.twitter.finagle.{Service, ServiceFactory, SimpleFilter, Stack}
import io.buoyant.namer.Metadata

object RewriteHostHeader {

  val headers = Set(
    Fields.Location,
    "Refresh" // Not present in Fields... Probably because it's not widely used but still...
  )

  case class Filter(host: String) extends SimpleFilter[Request, Response] {
    def apply(req: Request, svc: Service[Request, Response]) = {
      // Location and Refresh response header MUST contain absolute URLs
      // As we rewrite the Host header before passing it to a service there might be incorrect
      // values in those headers if service relies solely on Host header to generate them
      // Therefore we need to rewrite those response headers as nginx and Apache do
      val originalHost = req.host
      req.host = host
      svc(req).map { rsp =>
        for {
          orig <- originalHost
          header <- headers
          value <- rsp.headerMap.get(header)
        } {
          rsp.headerMap.set(header, value.replace(host, orig))
        }
        rsp
      }
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
