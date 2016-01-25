package io.buoyant.linkerd.admin

import com.twitter.finagle.http.HttpMuxer
import com.twitter.server.handler.ResourceHandler
import com.twitter.server.view.NotFoundView
import io.buoyant.linkerd.Linker

object LinkerdAdmin {
  def init(linker: Linker) {
    HttpMuxer.addHandler("/", new NotFoundView andThen new SummaryHandler(linker))
    HttpMuxer.addHandler(
      "/files/",
      StaticFilter andThen ResourceHandler.fromDirectoryOrJar(
        baseRequestPath = "/files/",
        baseResourcePath = "io/buoyant/linkerd/admin",
        localFilePath = "linkerd/admin/src/main/resources/io/buoyant/linkerd/admin"
      )
    )
    HttpMuxer.addHandler("/delegator", DelegateHandler.ui(linker))
    HttpMuxer.addHandler("/delegator.json", DelegateHandler.api)
    HttpMuxer.addHandler("/routers.json", new RouterHandler(linker))
    HttpMuxer.addHandler("/metrics", MetricsHandler)
  }
}
