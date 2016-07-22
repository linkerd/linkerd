package io.buoyant.namerd
package storage.consul

import com.twitter.finagle.{Dtab, Path}
import com.twitter.io.Buf
import com.twitter.util._
import io.buoyant.consul.v1._
import io.buoyant.namerd.DtabStore.{DtabNamespaceAlreadyExistsException, DtabNamespaceDoesNotExistException, DtabVersionMismatchException, Version}

class ConsulDtabStore(api: KvApi, root: Path, recursive: Boolean) extends DtabStore {

  /**
   * *
   * Generate list of  'ephemeral' dirs from the path - list of all intermediate dirs
   * For instance - for a/b/c that would be a/ and a/b/
   */
  private[this] def getDirs(key: String): Seq[String] = {
    def cycle(parts: Seq[String]): Stream[String] =
      if (parts.isEmpty) Stream.empty else parts.mkString("", "/", "/") #:: cycle(parts.init)
    cycle(key.split("/").toSeq.init).toList.toSeq
  }

  override def list(): Activity[Set[Ns]] = {
    def namespace(key: String): Ns = key.stripPrefix("/").substring(root.show.length)

    val run = Var.async[Activity.State[Set[Ns]]](Activity.Pending) { updates =>
      @volatile var running = true

      def cycle(index: Option[String]): Future[Unit] =
        if (running) {
          val separator = if (recursive) None else Some("/") // filter out nested keys in non-recursive mode
          api.list(s"${root.show}/", blockingIndex = index, separator = separator).transform {
            case Return(result) =>
              // Filter out dirs from 'root'
              val namespaces = result.value.map(namespace).filter { ns => recursive || !ns.endsWith("/") }
              // Generate 'ephemeral' namespaces - if we have a key a/b we want to report a/ as a namespace too
              val extraNamespaces = if (recursive) namespaces.map { ns => getDirs(ns) }.reduceLeft(_ ++ _) else Seq.empty
              updates() = Activity.Ok((namespaces ++ extraNamespaces).toSet)
              cycle(result.index)
            case Throw(e: NotFound) =>
              updates() = Activity.Ok(Set.empty[Ns])
              cycle(e.rsp.headerMap.get(Headers.Index))
            case Throw(e) =>
              updates() = Activity.Failed(e)
              cycle(None)
          }
        } else
          Future.Unit
      val pending = cycle(None)

      Closable.make { _ =>
        running = false
        pending.raise(new FutureCancelledException)
        Future.Unit
      }
    }

    Activity(run)
  }

  def create(ns: Ns, dtab: Dtab): Future[Unit] = {
    val key = s"${root.show}/${ns.stripSuffix("/")}" // cannot create 'dirs' - strip '/' suffix
    api.put(key, dtab.show, cas = Some("0")).flatMap { result =>
      if (result) Future.Done else Future.exception(new DtabNamespaceAlreadyExistsException(ns))
    }
  }

  def delete(ns: Ns): Future[Unit] = {
    // In non-recursive mode - always delete just a key
    // In recursive mode - delete a key or a dir depending on presence of '/' suffix
    val recursiveCall = recursive && ns.endsWith("/")
    val namespace = if (recursive) ns else ns.stripSuffix("/")

    val key = s"${root.show}/$namespace"
    api.get(key, recurse = recursiveCall).transform {
      case Return(_) => api.delete(key, recurse = recursive).unit
      case Throw(e: NotFound) => Future.exception(new DtabNamespaceDoesNotExistException(ns))
      case Throw(e) => Future.exception(e)
    }
  }

  def update(ns: Ns, dtab: Dtab, version: Version): Future[Unit] = {
    val key = s"${root.show}/${ns.stripSuffix("/")}" // cannot update 'dirs' - strip '/' suffix
    val Buf.Utf8(vstr) = version
    Try(vstr.toLong) match {
      case Return(_) =>
        api.put(key, dtab.show, cas = Some(vstr)).flatMap { result =>
          if (result) Future.Done else Future.exception(new DtabVersionMismatchException)
        }
      case _ => Future.exception(new DtabVersionMismatchException)
    }
  }

  def put(ns: Ns, dtab: Dtab): Future[Unit] = {
    val key = s"${root.show}/${ns.stripSuffix("/")}" // cannot put a 'dir' - strip '/' suffix
    api.put(key, dtab.show).unit
  }

  def readDtab(key: Key) = {
    if (key.DecodedValue.isDefined) {
      Try { Dtab.read(key.DecodedValue.get) } match {
        case Return(d: Dtab) => d
        case _ => Dtab.empty
      }
    } else Dtab.empty
  }

  def observe(ns: Ns): Activity[Option[VersionedDtab]] = {
    val (recursiveCall, namespace) = if (recursive && ns.endsWith("/")) (true, ns.stripSuffix("/")) else (false, ns)
    val key = s"${root.show}/$namespace"
    val run = Var.async[Activity.State[Option[VersionedDtab]]](Activity.Pending) { updates =>
      @volatile var running = true

      def cycle(index: Option[String]): Future[Unit] =
        if (running)
          api.get(key, recurse = recursiveCall, blockingIndex = index).transform {
            case Return(result) =>
              val version = Buf.Utf8(result.index.get)
              // Consul uses prefix-match - when fetching recursively /foo it will not only return /foo/a but also /foobar
              val pattern = s"${key.stripPrefix("/")}(/.*)?"
              val filteredKeys = result.value.filter(_.Key.get.matches(pattern))
              val sortedKeys = filteredKeys.sortBy[String](_.Key.get)
              val mergedDtab = sortedKeys.map(readDtab).reduceLeft(_ ++ _)
              updates() = Activity.Ok(Some(VersionedDtab(mergedDtab, version)))
              cycle(result.index)
            case Throw(e: NotFound) =>
              updates() = Activity.Ok(None)
              cycle(e.rsp.headerMap.get(Headers.Index))
            case Throw(e) =>
              updates() = Activity.Failed(e)
              cycle(None)
          }
        else
          Future.Unit
      val pending = cycle(None)

      Closable.make { _ =>
        running = false
        pending.raise(new FutureCancelledException)
        Future.Unit
      }
    }
    Activity(run)
  }
}
