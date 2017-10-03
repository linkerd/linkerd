package com.medallia.l5d.curatorsd.common

import java.util.concurrent.Callable
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import com.google.common.base.Enums
import com.google.common.cache.CacheBuilder
import com.medallia.servicediscovery.ServiceDiscovery
import com.medallia.servicediscovery.ServiceDiscoveryConfig
import com.medallia.servicediscovery.ServiceDiscoveryRegistrar.RegistrationFormat
import com.twitter.logging.Logger
import com.twitter.util.{Closable, Future, Time}

import scala.language.implicitConversions

object CuratorSDCommon {

  private val serviceDiscoveryCache = CacheBuilder.newBuilder().build[String, ServiceDiscoveryInfo]

  implicit def callable[T](f: () => T): Callable[T] = () => f()

  /**
   * @param zkConnectStr ZK connection string
   * @param backwardsCompatibility oldest format we should support. Default
   * @return Service Discovery set of objects which needs to be closed
   */
  def createServiceDiscovery(zkConnectStr: String, backwardsCompatibility: Option[String]): ServiceDiscoveryInfo = {

    val parsedBackwardsCompatibility = backwardsCompatibility
      .flatMap(format => Option(Enums
        .getIfPresent(classOf[RegistrationFormat], format)
        .orNull()))

    val serviceDiscoveryInfo = serviceDiscoveryCache.get(
      zkConnectStr,
      callable[ServiceDiscoveryInfo](() => ServiceDiscoveryInfo(zkConnectStr, parsedBackwardsCompatibility))
    )
    serviceDiscoveryInfo.addReference()
    serviceDiscoveryInfo
  }

  /** Unfortunately, Path doesn't allow empty elements. "_" means empty */
  def fromOptionalPathField(field: String): Option[String] =
    Some(field).filter(_ != "_")

  /** Unfortunately, Path doesn't allow empty elements. "_" means empty */
  def toOptionalPathField(value: Option[String]): String =
    value.getOrElse("_")

}

case class ServiceDiscoveryInfo(zkConnectStr: String, backwardsCompatibility: Option[RegistrationFormat]) extends RefCounted {

  private val log = Logger(getClass)

  private val serviceDiscoveryConfig = new ServiceDiscoveryConfig(zkConnectStr)
    .setBackwardsCompatibility(backwardsCompatibility.orNull)

  log.info("Starting service discovery with config %s", serviceDiscoveryConfig)
  val serviceDiscovery = new ServiceDiscovery(serviceDiscoveryConfig)

  protected override def performClose(): Unit = {
    log.info("Physically closing service discovery %s", zkConnectStr)
    serviceDiscovery.close()
    log.info("Service discovery physically closed")
  }
}

trait RefCounted extends Closable {

  private val refCount = new AtomicInteger()
  private val isClosed = new AtomicBoolean()

  override def close(deadline: Time): Future[Unit] = Future {
    this.synchronized {
      if (refCount.decrementAndGet() <= 0 && !isClosed.get()) {
        performClose()
        isClosed.set(true)
      }
    }
  }

  private[common] def addReference(): Unit = {
    this.synchronized {
      if (isClosed.get())
        throw new IllegalStateException(s"Already closed $this")
      val _ = refCount.incrementAndGet()
    }
  }

  protected def performClose(): Unit

}
