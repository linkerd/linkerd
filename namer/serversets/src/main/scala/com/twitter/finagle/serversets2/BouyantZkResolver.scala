package com.twitter.finagle.serverset2

import com.twitter.finagle.{Addr, Path, Resolver}
import com.twitter.finagle.serverset2.naming.ServersetPath
import com.twitter.util.Var

trait BouyantZkResolver {
  protected[this] def addrOf(
    zkHosts: String,
    zkPath: String,
    endpoint: Option[String],
    shardId: Option[Int]
  ): Var[Addr]

  def resolve(path: Path): Var[Addr] = ServersetPath.of(path) match {
    case Some(ServersetPath(zkHosts, zkPath, endpoint, shardId)) =>
      addrOf(zkHosts, zkPath.show, endpoint, shardId)
    case _ =>
      Var.value(Addr.Neg)
  }
}

class BouyantZkResolverImpl(zk2: Zk2Resolver) extends BouyantZkResolver {
  def this() = this(Resolver.get(classOf[Zk2Resolver]).get)

  protected[this] def addrOf(
    zkHosts: String,
    zkPath: String,
    endpoint: Option[String],
    shardId: Option[Int]
  ): Var[Addr] =
    zk2.addrOf(zkHosts, zkPath, endpoint, shardId)
}
