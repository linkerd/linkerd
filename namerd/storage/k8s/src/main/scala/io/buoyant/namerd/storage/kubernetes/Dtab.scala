package io.buoyant.namerd.storage.kubernetes

import com.twitter.finagle.Dentry
import io.buoyant.k8s.{KubeObject, ObjectMeta}

/**
 * This is the Dtab JSON format stored in the k8s thirdparty API. It should
 * largely mirror the JSON format returned from namerd HTTP APIs.
 */
case class Dtab(
  dentries: Seq[Dentry],
  kind: Option[String] = Some("DTab"),
  metadata: Option[ObjectMeta] = None,
  apiVersion: Option[String] = None
) extends KubeObject
