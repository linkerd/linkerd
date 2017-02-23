package io.buoyant.admin

import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.finagle.http.{Request, Response}

class AdminAssetsFilter(additionalElements: String = "") extends SimpleFilter[Request, Response] {
  def apply(req: Request, svc: Service[Request, Response]) = {
    val serviced = svc(req)
    serviced.map { res =>
      val wrappedHtml = s"""
          <script src="files/js/lib/jquery.min.js"></script>
          <script src="files/jquery-ui.min.js"></script>
          <script src='files/js/lib/bootstrap.min.js'></script>
          <link href="files/css/lib/bootstrap.min.css" rel="stylesheet">
          ${additionalElements}
          <div class="container">${res.contentString}</div>
          """.stripMargin

      res.contentString = wrappedHtml
      res
    }
  }
}
