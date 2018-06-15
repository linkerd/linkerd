package com.twitter.finagle.naming.buoyant

import com.twitter.finagle._
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.util.Future
import io.buoyant.namer.{DelegateTree, Delegator, RichActivity}

class RichNoBrokersAvailableException(
  path: Dst.Path,
  dtab: Option[Dtab],
  resolutions: Option[Seq[String]]
) extends RequestException {

  private[this] def formatDtab(d: Dtab): String =
    d.map { dentry =>
      s"  ${dentry.prefix.show} => ${dentry.dst.show}"
    }.mkString("\n")

  override def exceptionMessage(): String =
    s"""Unable to route request!

service name: ${path.path.show}
resolutions considered:
${resolutions.getOrElse(Seq("unknown")).map("  " + _).mkString("\n")}
dtab:
${formatDtab(dtab.getOrElse(Dtab.empty))}
base dtab:
${formatDtab(path.baseDtab)}
override dtab:
${formatDtab(path.localDtab)}
""".stripMargin
}

object RichNoBrokersAvailableException {

  def apply(path: Dst.Path, namer: NameInterpreter): Future[Nothing] = {
    namer match {
      case delegator: Delegator =>

        val dtab = delegator.dtab.toFuture
        val res = delegator.delegate(path.dtab, path.path).flatMap(resolutions)

        dtab.join(res).flatMap {
          case (d, r) => Future.exception(new RichNoBrokersAvailableException(path, Some(d), Some(r)))
        }
      case _ => Future.exception(new RichNoBrokersAvailableException(path, None, None))
    }
  }

  def resolutions(tree: DelegateTree[Name.Bound]): Future[Seq[String]] = {
    tree match {
      case DelegateTree.Leaf(path, _, Name.Bound(vaddr)) =>
        vaddr.changes.toFuture().map {
          case Addr.Bound(_, _) => Seq(s"${path.show} (bound)")
          case Addr.Failed(e) => Seq(s"${path.show} (exception: ${e.getMessage}")
          case Addr.Neg => Seq(s"${path.show} (neg)")
          case Addr.Pending => Seq(s"${path.show} (pending)")
        }
      case DelegateTree.Exception(path, _, e) => Future.value(Seq(s"${path.show} (exception: ${e.getMessage}"))
      case DelegateTree.Empty(path, _) => Future.value(Seq(s"${path.show} (empty)"))
      case DelegateTree.Fail(path, _) => Future.value(Seq(s"${path.show} (fail)"))
      case DelegateTree.Neg(path, _) => Future.value(Seq(s"${path.show} (neg)"))
      case DelegateTree.Delegate(_, _, next) => resolutions(next)
      case DelegateTree.Transformation(_, _, _, next) => resolutions(next)
      case DelegateTree.Union(_, _, children@_*) =>
        Future.collect {
          children.map { child =>
            resolutions(child.tree)
          }
        }.map(_.flatten)
      case DelegateTree.Alt(_, _, children@_*) =>
        Future.collect(children.map(resolutions)).map(_.flatten)
    }
  }
}
