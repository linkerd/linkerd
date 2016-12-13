package io.buoyant.namer

import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle._
import com.twitter.util.Activity
import java.net.InetAddress

class LocalhostNamer(idPrefix: Path) extends Namer {

  override def lookup(path: Path): Activity[NameTree[Name]] = {

    val target = NameInterpreter.global.bind(Dtab.empty, path)

    val transformer = new SubnetLocalTransformer(InetAddress.getLocalHost, Netmask("255.255.255.255"))

    target.flatMap(transformer.transform).map { tree =>
      tree.map { bound =>
        bound.id match {
          case id: Path => Name.Bound(bound.addr, idPrefix ++ id, bound.path)
          case _ => bound
        }
      }
    }
  }
}
