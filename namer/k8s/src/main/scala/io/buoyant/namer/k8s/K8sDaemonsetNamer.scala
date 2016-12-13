package io.buoyant.namer.k8s

import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle._
import com.twitter.util.Activity
import io.buoyant.namer.{Metadata, MetadataGatewayTransformer}

class K8sDaemonsetNamer(
  idPrefix: Path,
  k8sNamer: Namer
) extends Namer {

  override def lookup(path: Path): Activity[NameTree[Name]] = {

    val dsName = path.take(3)
    val targetName = path.drop(3)

    val ds = k8sNamer.bind(NameTree.Leaf(dsName))
    val target = NameInterpreter.global.bind(Dtab.empty, targetName)

    val transformer = new MetadataGatewayTransformer(ds, Metadata.nodeName)

    target.flatMap(transformer.transform).map { tree =>
      tree.map { bound =>
        bound.id match {
          case id: Path => Name.Bound(bound.addr, idPrefix ++ dsName ++ id, bound.path)
          case _ => bound
        }
      }
    }
  }
}
