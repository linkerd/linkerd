package io.buoyant.telemetry.requestLog

import com.google.common.cache.CacheBuilder
import com.twitter.finagle.tracing._
import com.twitter.util.Time
import scala.collection.JavaConverters._
import com.twitter.finagle.buoyant.Sampler

class RequestLogTracer(sampleRate: Double, capacity: Int) extends Tracer {

  private[this] val sampler = Sampler(sampleRate.toFloat)

  private[this] val cache = CacheBuilder.newBuilder().maximumSize(capacity).build[SpanId, Seq[Record]]()

  override def record(record: Record): Unit = {
    val traceId = record.traceId.traceId
    if (sampler(traceId.toLong) && acceptedAnnotations(record.annotation)) synchronized {
      cache.getIfPresent(traceId) match {
        case null => cache.put(traceId, Seq(record))
        case records => cache.put(traceId, records :+ record)
      }
    }
  }

  def getRequests: Seq[RequestMetadata] = synchronized {
    cache.asMap().asScala.values.flatMap(mkRequestMetadata).toSeq.sortBy { req =>
      -req.timestamp.inMillis
    }
  }

  private[this] val acceptedAnnotations: Annotation => Boolean = {
    case Annotation.ServerAddr(_) => true
    case Annotation.ClientAddr(_) => true
    case Annotation.BinaryAnnotation("router.label", _) => true
    case Annotation.BinaryAnnotation("namer.path", _) => true
    case Annotation.BinaryAnnotation("dst.id", _) => true
    case _ => false
  }

  private[this] def annotationNamed(annotations: Seq[Annotation], name: String): Option[String] = {
    annotations.collectFirst {
      case Annotation.BinaryAnnotation(n, r) if n == name => r.toString
    }
  }

  private[this] def mkRequestMetadata(records: Seq[Record]): Seq[RequestMetadata] = {

    val allServerAddrs = records.flatMap { record =>
      record.annotation match {
        case Annotation.ServerAddr(addr) => Some((record.traceId, addr))
        case _ => None
      }
    }

    records.groupBy(_.traceId.spanId).flatMap {
      case (span, recs) =>

        val clientAddrs = recs.flatMap { record =>
          record.annotation match {
            case Annotation.ClientAddr(addr) => Some((record.traceId, addr))
            case _ => None
          }
        }
        val serverAddrs = recs.flatMap { record =>
          record.annotation match {
            case Annotation.ServerAddr(addr) => Some((record.traceId, addr))
            case _ => None
          }
        }

        val annotations = recs.map(_.annotation)

        for {
          (sourceId, src) <- clientAddrs
          (serverId, srv) <- serverAddrs
          (destinationId, dst) <- allServerAddrs
          if destinationId.spanId != span && destinationId.parentId == span
          router <- annotationNamed(annotations, "router.label")
          logicalName <- annotationNamed(annotations, "namer.path")
          concreteName <- annotationNamed(annotations, "dst.id")
        } yield {
          val timestamp = recs.map(_.timestamp).minBy(_.inMillis)
          RequestMetadata(timestamp, src.toString, srv.toString, router, logicalName, concreteName, dst.toString)
        }
    }.toSeq
  }

  case class RequestMetadata(
    timestamp: Time,
    source: String,
    server: String,
    router: String,
    logicalName: String,
    concreteName: String,
    destination: String
  )

  override def sampleTrace(traceId: TraceId): Option[Boolean] = None
}
