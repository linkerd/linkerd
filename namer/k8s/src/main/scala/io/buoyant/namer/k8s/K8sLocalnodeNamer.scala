package io.buoyant.namer.k8s

import com.twitter.finagle._
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.util.Activity
import io.buoyant.namer.{Metadata, MetadataFiltertingNameTreeTransformer}

class K8sLocalnodeNamer(
  idPrefix: Path,
  nodeName: String
) extends Namer {

  override def lookup(path: Path): Activity[NameTree[Name]] = {

    val target = NameInterpreter.global.bind(Dtab.empty, path)

    val transformer = new MetadataFiltertingNameTreeTransformer(Metadata.nodeName, nodeName)

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
