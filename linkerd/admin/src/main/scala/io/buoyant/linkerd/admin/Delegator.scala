package io.buoyant.linkerd.admin

import com.twitter.finagle._
import com.twitter.finagle.http.{HttpMuxer, Request, Response}
import com.twitter.server.TwitterServer
import com.twitter.util.Future
import io.buoyant.linkerd.admin.names.WebDelegator

private object DelegateHandler {
  def render(dtab: () => Dtab) =
    AdminHandler.adminHtml(
      content = s"""
        <div class="row">
          <div class="col-lg-6">
            <div class="input-group path">
              <span class="input-group-addon" id="basic-addon1">Path</span>
              <input type="text" class="form-control" id="path-input" placeholder="/path/to/resource" aria-describedby="basic-addon1">
              <span class="input-group-btn">
                <button class="btn btn-default go" type="button">Go!</button>
              </span>
            </div>
          </div>
        </div>
        <div class="well well-sm dtab">
          <h4 class="header">
            Dtab
            <button id="edit-dtab-btn" class="btn btn-info btn-sm">Edit</button>
            <button id="save-dtab-btn" class="btn btn-success btn-sm hide disabled">Save</button>
          </h4>
          <div id="dtab"></div>
          <div id="dtab-edit" class="hide">
            <textarea type="text" class="form-control" id="dtab-input" rows="${dtab().size}" placeholder=""></textarea>
          </div>
          </div>
        </div>
        <div class="result">
        </div>
      """,
      tailContent = s"""
        <script id="data" type="application/json">${WebDelegator.Codec.writeStr(dtab())}</script>
      """,
      javaScripts = Seq("lib/handlebars-v4.0.5.js", "lib/smoothie.js", "dtab_viewer.js", "delegate.js"),
      csses = Seq("delegator.css")
    )
}

class DelegateHandler(dtab: () => Dtab) extends Service[Request, Response] {
  import DelegateHandler._

  override def apply(req: Request): Future[Response] = {
    AdminHandler.mkResponse(render(dtab))
  }
}

trait DelegatorAdmin { self: TwitterServer =>
  premain {
    HttpMuxer.addHandler("/delegator", new DelegateHandler(() => Dtab.base))
    HttpMuxer.addHandler("/delegator.json", new WebDelegator())
  }
}
