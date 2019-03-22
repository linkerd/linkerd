package io.buoyant.namerd

import com.twitter.finagle.Service
import com.twitter.finagle.http.{MediaType, Request, Response}
import com.twitter.util.Future
import io.buoyant.namer.RichActivity

class DtabListHandler(
  store: DtabStore
) extends Service[Request, Response] {

  override def apply(req: Request): Future[Response] =
    store.list().toFuture.map { list =>
      val response = Response()
      response.contentType = MediaType.Html
      response.contentString = render(list.toSeq.sorted)
      response
    }

  def render(list: Iterable[String]) = {
    val content = if (list.isEmpty) {
      s"""
        <h2 class="router-label-title">No namespaces found</h2>
        <p>This is likely due to one of two things:</p>
        <ol>
          <li>no namespaces have been created in the backend dtab store</li>
          <li>namerd is not configured to connect to the appropriate backend dtab store</li>
        </ol>
        <p>For more information, please consult the
          <a href="https://linkerd.io/config/latest/namerd">namerd documentation</a>.
        </p>
      """
    } else {
      s"""
        <h2 class="router-label-title">Namespaces:</h2>
        <div class="list-group">
        ${
        list.map { ns => s"""<a class="list-group-item" href="dtab/$ns">$ns</a>""" }.mkString("")
      }
        </div>
      """
    }

    s"""
      <div class="row">
        <div class="col-lg-6">
          $content
        </div>
    </div>
    """
  }
}
