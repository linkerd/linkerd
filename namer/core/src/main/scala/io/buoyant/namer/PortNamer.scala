package io.buoyant.namer

import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle._
import com.twitter.io.Buf
import com.twitter.logging.Logger
import com.twitter.util.{Activity, Return, Throw, Try}

class PortNamer(idPrefix: Path) extends Namer {

  private[this] val log = Logger()

  override def lookup(path: Path): Activity[NameTree[Name]] = {

    val port = Try {
      path.take(1) match {
        case Path.Utf8(portStr) => portStr.toInt
      }
    }
    val targetName = path.drop(1)
    val target = NameInterpreter.global.bind(Dtab.empty, targetName)

    port match {
      case Return(p) =>
        val transformer = new PortTransformer(p)
        target.flatMap(transformer.transform).map { tree =>
          tree.map { bound =>
            bound.id match {
              case id: Path => Name.Bound(bound.addr, idPrefix ++ path.take(1) ++ id, bound.path)
              case _ => bound
            }
          }
        }
      case Throw(e) =>
        log.error(e, "Invalid path %s", path.show)
        Activity.value(NameTree.Neg)
    }
  }
}
