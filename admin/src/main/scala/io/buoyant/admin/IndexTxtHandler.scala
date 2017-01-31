package io.buoyant.admin

import com.twitter.finagle.Service
import com.twitter.finagle.http.{MediaType, Request, Response}
import com.twitter.io.Buf
import com.twitter.util.Future

class IndexTxtHandler(paths: Seq[String])
  extends Service[Request, Response] {

  def apply(req: Request): Future[Response] = {
    val rsp = Response()
    rsp.version = req.version
    rsp.contentType = MediaType.Txt
    rsp.content = Buf.Utf8(paths.mkString("", "\n", "\n"))
    Future.value(rsp)
  }
}
