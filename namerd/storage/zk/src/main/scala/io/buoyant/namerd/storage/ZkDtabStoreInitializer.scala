package io.buoyant.namerd.storage

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.conversions.DurationOps._
import com.twitter.finagle.Stack
import com.twitter.finagle.serverset2.client.buoyant.ZkDtabStore
import io.buoyant.config.types.HostAndPort
import io.buoyant.namerd.{DtabStore, DtabStoreConfig, DtabStoreInitializer}

class ZkDtabStoreInitializer extends DtabStoreInitializer {
  override def configClass = classOf[ZkConfig]
  override def configId = "io.l5d.zk"
}

case class ZkConfig(
  zkAddrs: Seq[HostAndPort],
  pathPrefix: Option[String],
  sessionTimeoutMs: Option[Int],
  authInfo: Option[AuthInfo],
  acls: Option[Seq[Acl]]
) extends DtabStoreConfig {

  @JsonIgnore val sessionTimeout = sessionTimeoutMs.map(_.millis)

  @JsonIgnore
  override def mkDtabStore(params: Stack.Params): DtabStore = new ZkDtabStore(
    zkAddrs.map(_.toString).mkString(","),
    pathPrefix.getOrElse("/dtabs"),
    sessionTimeout,
    authInfo,
    acls.getOrElse(Seq(Acl.AnyoneUnsafe))
  )
}

case class AuthInfo(scheme: String, auth: String)

case class Acl(scheme: String, id: String, perms: String) {
  if (!Acl.validPerms(perms))
    throw new IllegalArgumentException(s"$perms is not a valid permission string")
}

object Acl {
  private[this] val legalPerms = "^[crwda]+$".r.pattern.asPredicate.test _
  private[this] def noDuplicatePerms(perms: String): Boolean = perms.distinct == perms
  def validPerms(perms: String): Boolean = legalPerms(perms) && noDuplicatePerms(perms)
  val AnyoneUnsafe = Acl("world", "anyone", "crwda")
}
