package io.buoyant.etcd

import com.twitter.conversions.DurationOps._
import com.twitter.finagle.{Path, Service}
import com.twitter.finagle.http._
import com.twitter.finagle.Backoff
import com.twitter.io.Buf
import com.twitter.util._

import scala.util.control.NonFatal

case class BackoffsExhausted(key: Path, throwable: Throwable)
  extends Exception(key.show, throwable)

object Key {

  /*
   * Helpers for building request params
   */

  private val Params = Seq.empty[(String, String)]

  private def trueParam(name: String, cond: Boolean): Option[(String, String)] =
    if (cond) Some(name -> "true") else None

  private def falseParam(name: String, cond: Boolean): Option[(String, String)] =
    if (!cond) Some(name -> "false") else None

  private[this] val dirParam = ("dir" -> "true")
  private def dirOrValueParam(v: Option[Buf]): (String, String) = v match {
    case None => dirParam
    case Some(buf) =>
      val Buf.Utf8(v) = buf
      ("value" -> v)
  }

  private def ttlParam(ttl: Option[Duration]): Option[(String, String)] = ttl.map {
    case Duration.Zero => "ttl" -> ""
    case ttl => "ttl" -> ttl.inSeconds.toString
  }

}

class Key(key: Path, client: Service[Request, Response]) {

  import Etcd._
  import Key._

  def path: Path = key

  def key(path: Path): Key = new Key(key ++ path, client)
  def key(name: String): Key = key(Path.read(name))

  private[this] lazy val uriPath = keysPrefixPath ++ key

  /**
   * Set the contents of a key.
   *
   * If `recursive` is true and this key is a directory, the returned
   * Node contains the entire tree of children.
   *
   * If `wait` is true and `waitIndex` is specified, a response is not
   * received until the node (or one of its children, if `recursive`
   * is specified) is updated. If `wait` is true and `waitIndex` is
   * not specified, a response is not received until the next update
   * to this node (or its children, if `recursive`).
   *
   * If `quorum` is specified, etcd will ensure that the etcd instance
   * is at quorum with the cluster.
   */
  def get(
    recursive: Boolean = false,
    wait: Boolean = false,
    waitIndex: Option[Long] = None,
    quorum: Boolean = false
  ): Future[NodeOp] = {
    val params = Params ++
      trueParam("recursive", recursive) ++
      trueParam("quorum", quorum) ++
      trueParam("wait", wait) ++
      waitIndex.map("waitIndex" -> _.toString)
    val req = mkReq(uriPath, params = params)
    req.accept = MediaType.Json
    client(req).flatMap { rsp => Future.const(NodeOp.mk(req, rsp, key, params)) }
  }

  /**
   * Set the contents of a key.
   *
   * If value is None, the key is treated as a directory.  In order to
   * unset the value of a data node, use `Some(Buf.Empty)`.
   *
   * Optionally, a `ttl` may be specified to inform etcd to remove the
   * node after some time period (only second-granularity is supported
   * by etcd).
   *
   * If `prevExist` is true, the node operation will fail if the node
   * does not already exist.
   */
  def set(
    value: Option[Buf],
    ttl: Option[Duration] = None,
    prevExist: Boolean = false
  ): Future[NodeOp] = {
    val params = Params ++
      ttlParam(ttl) ++
      trueParam("prevExist", prevExist) :+
      dirOrValueParam(value)
    val req = mkReq(uriPath, Method.Put, params)
    client(req).flatMap { rsp => Future.const(NodeOp.mk(req, rsp, key, params)) }
  }

  /**
   * Create a new key.
   *
   * If the key exists, an error is returned.
   *
   * If value is None, the key is treated as a directory.  In order to
   * create an empty data node, use `Some(Buf.Empty)`.
   *
   * Optionally, a `ttl` may be specified to inform etcd to remove the
   * node after some time period (only second-granularity is supported
   * by etcd).
   */
  def create(
    value: Option[Buf],
    ttl: Option[Duration] = None
  ): Future[NodeOp] = {
    val params = Params ++
      ttlParam(ttl) :+
      dirOrValueParam(value) :+
      ("prevExist" -> "false")
    val req = mkReq(uriPath, Method.Put, params)
    client(req).flatMap { rsp => Future.const(NodeOp.mk(req, rsp, key, params)) }
  }

  /**
   * Set the node's data if the provided preconditions apply to the
   * existing state of the node.
   *
   * If `prevIndex` is specified, the current node must have the
   * provided `index` value.
   *
   * If `prevValue` is specified, the current node must have the
   * provided value.
   *
   * If `prevExist` is false, the node is not required to exist.
   */
  def compareAndSwap(
    value: Buf,
    prevIndex: Option[Long] = None,
    prevValue: Option[Buf] = None,
    prevExist: Boolean = true
  ): Future[NodeOp] = {
    require(prevIndex.isDefined || prevValue.isDefined || !prevExist)

    val Buf.Utf8(vstr) = value
    val params = Seq("value" -> vstr) ++
      prevIndex.map("prevIndex" -> _.toString) ++
      prevValue.map { case Buf.Utf8(v) => "prevValue" -> v } ++
      falseParam("prevExist", prevExist)

    val req = mkReq(uriPath, Method.Put, params)
    client(req).flatMap { rsp => Future.const(NodeOp.mk(req, rsp, key, params)) }
  }

  /**
   * Delete a node.
   *
   * If `dir` is not true and the key is a directory, this will
   * operation fail.
   *
   * If `dir` and `recursive` are true, the entire tree is deleted.
   */
  def delete(
    dir: Boolean = false,
    recursive: Boolean = false
  ): Future[NodeOp] = {
    val params = Params ++
      trueParam("dir", dir) ++
      trueParam("recursive", recursive)
    val req = mkReq(uriPath, Method.Delete, params)
    client(req).flatMap { rsp => Future.const(NodeOp.mk(req, rsp, key, params)) }
  }

  /**
   * An Event constructed by watching an etcd key.
   *
   * If `recursive` is true, the key's subtree is observed for
   * changes.
   *
   * When an unexpected error is encountered communicating with the
   * API, the failure is published on the Event and the `backoff`
   * stream is used to compute the time to wait before retrying. If
   * the `backoff` stream is exhausted or a fatal error is
   * encountered, it is reported and polling stops.
   *
   * The Event is not reference-counted, so each observer initiates
   * its own polling loop. This ensures that the initial state of a
   * tree is reported properly.
   */
  def events(
    recursive: Boolean = false,
    backoff: Backoff = Backoff.exponentialJittered(10.millis, 10.minutes)
  ): Event[Try[NodeOp]] = new Event[Try[NodeOp]] {
    private[this] val origBackoff = backoff

    def register(witness: Witness[Try[NodeOp]]) = {
      @volatile var closed = false

      def loop(idx: Option[Long], backoff: Backoff): Future[Unit] =
        if (!closed) {
          get(recursive, wait = idx.isDefined, waitIndex = idx).transform {
            case note@Return(op) =>
              witness.notify(note)
              loop(Some(op.etcd.index + 1), origBackoff)

            case note@Throw(ApiError(ApiError.KeyNotFound, _, _, idx)) =>
              witness.notify(note)
              loop(Some(idx + 1), origBackoff)

            case Throw(ApiError(ApiError.EventIndexCleared, _, _, idx)) =>
              // no need to notify on this, the we'll catch up on the next read.
              loop(Some(idx + 1), origBackoff)

            case note@Throw(NonFatal(e)) =>
              if (backoff.isExhausted) {
                witness.notify(Throw(BackoffsExhausted(key, e)))
                Future.Unit
              } else {
                witness.notify(note)
                loop(None, backoff.next)
              }

            case note@Throw(_) =>
              witness.notify(note)
              Future.Unit
          }
        } else Future.Unit

      val pending = loop(None, origBackoff)

      Closable.make { _ =>
        closed = true
        pending.raise(new FutureCancelledException)
        Future.Unit
      }
    }

  }

  // TODO: removed until a concrete use for this has been identified
  // to determine whether this makes sense.
  /*
   * Watch a single node for updates.
   *
   * Observation of the returned Activity is reference-counted so that
   * multiple concurrent observers will not incur unnecessary requests
   * against the API.
   */
  // def watch(backoff: Stream[Duration] = Stream.empty): Activity[NodeOp] = {
  //   val event = events(false, backoff)
  //   val states = Var.async[Activity.State[NodeOp]](Activity.Pending) { state =>
  //     event.respond { op =>
  //       state() = op match {
  //         case Return(op) => Activity.Ok(op)
  //         case Throw(e) => Activity.Failed(e)
  //       }
  //     }
  //   }
  //   Activity(states)
  // }

  // TODO
  //
  // Slightly more complicated since it requires aggregating/updating
  // state across updates.
  //
  // def watchTree(backoff: Stream[Duration] = Stream.empty)
}
