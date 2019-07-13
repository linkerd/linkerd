package io.buoyant.consul

import com.twitter.finagle.http.{Chunk, HeaderMap}
import com.twitter.finagle.{Service, SimpleFilter, http}
import com.twitter.io.{Buf, Reader}
import com.twitter.util.Future

package object v1 {
  val versionString = "v1"
  type Client = Service[http.Request, http.Response]
  type IndexedServiceNodes = Indexed[Seq[ServiceNode]]
  type IndexedServiceMap = Indexed[Map[String, Seq[String]]]

  trait ConsulApiError extends Throwable {
    def rsp: http.Response

    override def toString: String =
      s"${this.getClass.getSimpleName}(${rsp.statusCode}: ${rsp.contentString})"
  }
  case class UnexpectedResponse(rsp: http.Response) extends ConsulApiError
  case class NotFound(rsp: http.Response) extends ConsulApiError
  case class Forbidden(rsp: http.Response) extends ConsulApiError

  private[v1] val apiErrorFilter = new SimpleFilter[http.Request, http.Response] {

    def apply(request: http.Request, service: Client): Future[http.Response] = {
      service(request).flatMap { response: http.Response =>
        response.status match {
          case http.Status.Ok => Future.value(response)
          case http.Status.Forbidden => Future.exception(Forbidden(response))
          case http.Status.NotFound => Future.exception(NotFound(response))
          case _ => Future.exception(UnexpectedResponse(response))
        }
      }
    }
  }

  implicit class RichChunkReader(val r: Reader[Chunk]) extends AnyVal {
    def accumulate: Future[(Buf, Option[HeaderMap])] = {
      def loop(acc: Buf, trailers: Option[HeaderMap]): Future[(Buf, Option[HeaderMap])] =
        r.read().flatMap {
          case Some(chunk) =>
            if (chunk.isLast && chunk.trailers.nonEmpty)
              loop(acc.concat(chunk.content), Some(chunk.trailers))
            else
              loop(acc.concat(chunk.content), None)
          case None =>
            Future.value(acc -> trailers)
        }
      loop(Buf.Empty, None)
    }
  }

}
