package io.buoyant.namerd.iface

import com.google.common.cache.{CacheLoader, RemovalNotification, RemovalListener, CacheBuilder}
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle._
import com.twitter.util.{Activity, Closable}
import io.buoyant.namerd.Ns

private[iface] class NameInterpreterCache(delegate: Ns => NameInterpreter, namers: Map[Path, Namer]) {

  private[this] case class NsPath(ns: Ns, path: Path)
  private[this] case class NameActClose(activity: Activity[NameTree[Name.Bound]], closable: Closable)

  def getBind(ns: String, path: Path, extraDtab: Option[String]): Activity[NameTree[Name.Bound]] =
    extraDtab match {
      case Some(dtab) => delegate(ns).bind(Dtab.read(dtab), path)
      case _ => bindingCache.get(NsPath(ns, path)).activity
    }

  def getAddr(ns: Ns, path: Path): Activity[Addr] =
    addrCache.get(NsPath(ns, path)).activity

  private[this] val bindingCacheSize = 1000
  private[this] val addrCacheSize = 1000

  private[this] val bindingCache = CacheBuilder.newBuilder()
    .maximumSize(bindingCacheSize)
    .removalListener(new RemovalListener[NsPath, NameActClose] {
      override def onRemoval(notification: RemovalNotification[NsPath, NameActClose]): Unit = {
        val _ = notification.getValue.closable.close()
      }
    })
    .build[NsPath, NameActClose](
      new CacheLoader[NsPath, NameActClose] {
        override def load(key: NsPath): NameActClose = {
          val act = delegate(key.ns).bind(Dtab.empty, key.path)
          // we want to keep the activity observed as long as it's in the cache
          val closable = act.run.changes.respond(_ => ())
          NameActClose(act, closable)
        }
      }
    )

  private[this] case class AddrActClose(activity: Activity[Addr], closable: Closable)

  private[this] val DefaultNamer: (Path, Namer) = Path.empty -> Namer.global

  private[this] def bindAddrId(id: Path): Activity[NameTree[Name.Bound]] = {
    val (pfx, namer) = namers.find { case (p, _) => id.startsWith(p) }.getOrElse(DefaultNamer)
    namer.bind(NameTree.Leaf(id.drop(pfx.size)))
  }

  private[this] val addrCache = CacheBuilder.newBuilder()
    .maximumSize(addrCacheSize)
    .removalListener(new RemovalListener[NsPath, AddrActClose] {
      override def onRemoval(notification: RemovalNotification[NsPath, AddrActClose]): Unit = {
        val _ = notification.getValue.closable.close()
      }
    })
    .build[NsPath, AddrActClose](
      new CacheLoader[NsPath, AddrActClose] {
        override def load(key: NsPath): AddrActClose = {
          val act = bindAddrId(key.path).flatMap {
            case NameTree.Leaf(bound) => Activity(bound.addr.map(Activity.Ok(_)))
            case NameTree.Empty => Activity.value(Addr.Bound())
            case NameTree.Fail => Activity.exception(new Exception("name tree failed"))
            case NameTree.Neg => Activity.value(Addr.Neg)
            case NameTree.Alt(_) | NameTree.Union(_) =>
              Activity.exception(new Exception(s"${key.path.show} is not a concrete bound id"))
          }.flatMap {
            case Addr.Pending => Activity.pending
            case addr => Activity.value(addr)
          }
          // we want to keep the activity observed as long as it's in the cache
          val closable = act.run.changes.respond(_ => ())
          AddrActClose(act, closable)
        }
      }
    )

}
