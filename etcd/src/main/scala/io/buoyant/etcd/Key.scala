package io.buoyant.etcd

import com.twitter.finagle.{Path, Service}
import com.twitter.finagle.http._
import com.twitter.io.Buf
import com.twitter.util._
import scala.collection.JavaConverters._

case class BackoffsExhausted(key: Path, throwable: Throwable)
  extends Exception(key.show, throwable)

class Key(key: Path, client: Service[Request, Response]) {

  import Etcd._

  def path: Path = key

  def key(path: Path): Key = new Key(key ++ path, client)
  def key(name: String): Key = key(Path.read(name))

  private[this] val noParams = Seq.empty[(String, String)]

  /** Get a key */
  def get(
    recursive: Boolean = false,
    wait: Boolean = false,
    waitIndex: Option[Long] = None,
    quorum: Boolean = false
  ): Future[NodeOp] = {
    var params = noParams
    if (quorum) {
      params = params :+ "quorum" -> "true"
    }
    if (recursive) {
      params = params :+ "recursive" -> "true"
    }
    if (wait) {
      params = params :+ ("wait" -> "true")
    }
    for (wait <- waitIndex) {
      params = params :+ ("waitIndex" -> wait.toString)
    }
    val req = mkReq(keysPrefixPath ++ key, params = params)

    req.headerMap("accept") = MediaType.Json
    client(req).flatMap(toNodeOp(req, _, key, params))
  }

  /*
   * Set the contents of a key.
   *
   * If value is None, the key is treated as a directory.  In order to unset the value
   * of a data node, use `Some(Buf.Empty)`.
   *
   * Optionally, a `ttl` may be specified to inform etcd to remove the node after some
   * time period (only second-granularity is supported by etcd).
   *
   * If the `prevExist` flag is set to true, the node operation will fail if the node does not
   * already exist.
   */
  def set(
    value: Option[Buf],
    ttl: Option[Duration] = None,
    prevExist: Boolean = false
  ): Future[NodeOp] = {
    var params = noParams
    value match {
      case Some(Buf.Utf8(value)) =>
        params = params :+ "value" -> value

      case _ =>
        params = params :+ "dir" -> "true"
    }
    for (ttl <- ttl) {
      val v = if (ttl == Duration.Zero) "" else ttl.inSeconds.toString
      params = params :+ "ttl" -> v
      if (prevExist) {
        params = params :+ "prevExist" -> "true"
      }
    }

    val req = mkReq(keysPrefixPath ++ key, Method.Put, params)
    client(req).flatMap(toNodeOp(req, _, key, params))
  }

  def create(
    value: Option[Buf],
    ttl: Option[Duration] = None
  ): Future[NodeOp] = {
    var params = noParams
    value match {
      case Some(Buf.Utf8(value)) =>
        params = params :+ ("value" -> value)
      case None =>
        params = params :+ ("dir" -> "true")
    }
    for (ttl <- ttl) {
      params = params :+ ("ttl" -> ttl.inSeconds.toString)
    }

    val req = mkReq(keysPrefixPath ++ key, Method.Post, params)
    client(req).flatMap(toNodeOp(req, _, key, params))
  }

  def compareAndSwap(
    value: Buf,
    prevIndex: Option[Long] = None,
    prevValue: Option[Buf] = None,
    prevExist: Boolean = true
  ): Future[NodeOp] = {
    require(prevIndex.isDefined || prevValue.isDefined || !prevExist)

    var params = noParams
    for (i <- prevIndex) {
      params = params :+ "prevIndex" -> i.toString
    }
    for (Buf.Utf8(v) <- prevValue) {
      params = params :+ "prevValue" -> v
    }
    if (!prevExist) {
      params = params :+ "prevExist" -> "false"
    }
    val Buf.Utf8(v) = value
    params = params :+ "value" -> v

    val req = mkReq(keysPrefixPath ++ key, Method.Put, params)
    client(req).flatMap(toNodeOp(req, _, key, params))
  }

  def delete(
    dir: Boolean = false,
    recursive: Boolean = false
  ): Future[NodeOp] = {
    var params = noParams
    if (dir) {
      params = params :+ ("dir" -> "true")
      if (recursive) {
        params = params :+ ("recursive" -> "true")
      }
    }

    val req = mkReq(keysPrefixPath ++ key, Method.Delete, params)
    client(req).flatMap(toNodeOp(req, _, key, params))
  }

  private[this] def getIndex(node: Node): Long = node match {
    case Data(_, idx, _, _, _) => idx
    case Dir(_, idx, _, _, nodes) => nodes.foldLeft(idx)(_ max getIndex(_))
  }

  def events(
    recursive: Boolean = false,
    backoff: Stream[Duration] = Stream.empty
  ): Event[Try[NodeOp]] = new Event[Try[NodeOp]] {
    private[this] val origBackoff = backoff

    def register(witness: Witness[Try[NodeOp]]) = {
      @volatile var closing = false
      @volatile var currentOp: Future[NodeOp] = Future.never

      def loop(idx: Option[Long], backoff: Stream[Duration]): Unit =
        if (!closing) {
          val op = get(recursive, wait = idx.isDefined, waitIndex = idx)
          currentOp = op
          op respond {
            case note@Return(op) =>
              witness.notify(note)
              loop(Some(getIndex(op.node) + 1), origBackoff)

            case note@Throw(ApiError(ApiError.KeyNotFound, _, _, idx)) =>
              witness.notify(note)
              loop(Some(idx + 1), origBackoff)

            case note@Throw(ApiError(ApiError.EventIndexCleared, _, _, idx)) =>
              loop(Some(idx + 1), origBackoff)

            case note@Throw(NonFatal(e)) =>
              backoff match {
                case wait #:: backoff =>
                  witness.notify(note)
                  loop(None, backoff)

                case _ =>
                  witness.notify(Throw(BackoffsExhausted(key, e)))
              }

            case note@Throw(_) =>
              witness.notify(note)
          }
        }

      loop(None, origBackoff)

      Closable.make { _ =>
        closing = true
        currentOp.raise(new FutureCancelledException)
        Future.Unit
      }
    }

  }

  def watch(backoff: Stream[Duration] = Stream.empty): Activity[NodeOp] =
    Activity(Var.async[Activity.State[NodeOp]](Activity.Pending) { state =>
      events(false, backoff) respond { op =>
        state() = op match {
          case Throw(e) => Activity.Failed(e)
          case Return(op) => Activity.Ok(op)
        }
      }
    })

}
