package io.buoyant.namer

import com.twitter.finagle.Name.Bound
import com.twitter.finagle.{Addr, Address, NameTree}
import com.twitter.util.Activity
import java.net.InetSocketAddress

trait FilteringTransformer extends DelegatingNameTreeTransformer {

  def select(addr: InetSocketAddress): Boolean

  override protected def transformDelegate(tree: DelegateTree[Bound]): DelegateTree[Bound] =
    tree.map { bound =>
      val vaddr = bound.addr.map {
        case Addr.Bound(addresses, meta) =>
          val filtered = addresses.filter {
            case Address.Inet(isa, _) => select(isa)
            case _ => true
          }
          Addr.Bound(filtered, meta)
        case addr => addr
      }
      Bound(vaddr, bound.id, bound.path)
    }

  override protected def transform(tree: NameTree[Bound]): Activity[NameTree[Bound]] =
    Activity.value(
      tree.map { bound =>
        val vaddr = bound.addr.map {
          case Addr.Bound(addresses, meta) =>
            val filtered = addresses.filter {
              case Address.Inet(isa, _) => select(isa)
              case _ => true
            }
            Addr.Bound(filtered, meta)
          case addr => addr
        }
        Bound(vaddr, bound.id, bound.path)
      }
    )
}
