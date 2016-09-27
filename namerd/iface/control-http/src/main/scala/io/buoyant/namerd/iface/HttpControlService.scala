package io.buoyant.namerd.iface

import com.twitter.finagle.http._
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.{Status => _, _}
import com.twitter.io.Buf
import com.twitter.util._
import io.buoyant.namerd.iface.HttpControlService.InvalidPathException
import io.buoyant.namerd.{DtabStore, Ns}

trait NsPathUri {
  val prefix: String
  lazy val prefixSlash = s"$prefix/"

  def unapply(request: Request): Option[(Option[Ns], Path)] = {
    if (request.path.startsWith(prefix)) {
      val ns = if (request.path.startsWith(prefixSlash)) {
        Some(request.path.stripPrefix(prefixSlash))
      } else {
        None
      }
      val path = try {
        Path.read(request.getParam("path"))
      } catch {
        case e: IllegalArgumentException =>
          throw InvalidPathException(request.getParam("path"), e)
      }
      Some(ns, path)
    } else {
      None
    }
  }
}

class HttpControlService(storage: DtabStore, delegate: Ns => NameInterpreter, namers: Map[Path, Namer])
  extends Service[Request, Response] {
  import HttpControlService._

  val cache = new NameInterpreterCache(delegate, namers)

  val dtabHandler = new DtabHandler(storage)
  val bindHandler = new BindHandler(cache)
  val addrHandler = new AddrHandler(cache)
  val resolveHandler = new ResolveHandler(cache)
  val delegateHandler = new DelegateHandler(delegate, namers)
  val boundNamesHandler = new BoundNamesHandler(namers)

  def apply(req: Request): Future[Response] = Future(req match {
    case DtabUri(_, _) => dtabHandler(req)
    case BindUri(_, _) => bindHandler(req)
    case AddrUri(_, _) => addrHandler(req)
    case ResolveUri(_, _) => resolveHandler(req)
    case DelegateUri(_, _) => delegateHandler(req)
    case BoundNamesUri(_) => boundNamesHandler(req)
    // invalid uri/method
    case _ => NotFound
  }).flatten.handle {
    case ex@InvalidPathException(path, _) =>
      val resp = Response(Status.BadRequest)
      resp.contentString = ex.getMessage
      resp
  }
}

object HttpControlService {

  val apiPrefix = "/api/1"

  case class InvalidPathException(path: String, underlying: Exception)
    extends Exception(s"Invalid path: $path / ${underlying.getMessage}", underlying)

  private[iface] def streamingRespF[T](
    values: Event[Try[T]],
    contentType: Option[String] = None
  )(render: T => Future[Buf]): Future[Response] = {
    val resp = Response()
    for (ct <- contentType) resp.contentType = ct
    resp.setChunked(true)
    val writer = resp.writer
    // closable is a handle to the values observation so that we can close the observation when the
    // streaming connection is terminated
    @volatile var closable: Closable = Closable.nop
    // calls to writer.write must be flatMapped together to ensure proper ordering and backpressure
    // writeFuture is an accumulator of those flatMapped Futures
    @volatile var writeFuture: Future[Unit] = Future.Unit
    closable = values.dedup.respond {
      case Return(t) =>
        writeFuture = writeFuture.before {
          render(t).flatMap {
            case Buf.Empty => Future.Unit
            case buf =>
              writer.write(buf).onFailure { _ =>
                val _ = closable.close()
              }
          }
        }
      case Throw(e) =>
        val _ = writer.write(Buf.Utf8(e.getMessage).concat(newline)).before(writer.close())
        val __ = closable.close() // https://issues.scala-lang.org/browse/SI-7691
    }
    Future.value(resp)
  }

  private[iface] def streamingResp[T](
    values: Event[Try[T]],
    contentType: Option[String] = None
  )(render: T => Buf): Future[Response] =
    streamingRespF(values, contentType) { t =>
      Future.value(render(t))
    }

  private[iface] def isStreaming(req: Request): Boolean = req.getBooleanParam("watch")

  private[iface] val newline = Buf.Utf8("\n")

  private[iface] val NotFound = Future.value(Response(Status.NotFound))
}

