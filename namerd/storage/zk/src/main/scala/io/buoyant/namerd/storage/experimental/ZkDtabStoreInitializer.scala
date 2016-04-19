package io.buoyant.namerd.storage.experimental

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.conversions.time._
import com.twitter.finagle.serverset2.buoyant.ZkDtabStore
import io.buoyant.namerd.{DtabStore, DtabStoreConfig, DtabStoreInitializer}

class ZkDtabStoreInitializer extends DtabStoreInitializer {
  override def configClass = classOf[zk]
}

case class zk(
  hosts: Seq[String],
  pathPrefix: Option[String],
  sessionTimeoutMs: Option[Int],
  authInfo: Option[AuthInfo],
  acls: Option[Seq[Acl]]
) extends DtabStoreConfig {

  @JsonIgnore val sessionTimeout = sessionTimeoutMs.map(_.millis)

  @JsonIgnore
  override def mkDtabStore: DtabStore = new ZkDtabStore(
    hosts.mkString(","),
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
  val validPerms = "[crwda]+".r.pattern.asPredicate.test _
  val AnyoneUnsafe = Acl("crwda", "world", "anyone")
}
