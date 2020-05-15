package io.buoyant.telemetry.recentRequests

import com.google.common.cache.{CacheBuilder, CacheLoader}
import com.twitter.finagle.tracing._
import com.twitter.util.Time
import scala.jdk.CollectionConverters._
import com.twitter.finagle.buoyant.Sampler
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * This tracer keeps an in-memory record of requests by storing trace records by traceId.  Metadata
 * about those requests can then be reconstructed based on the stored records.
 */
class RecentRequetsTracer(sampleRate: Double, capacity: Int) extends Tracer {

  private[this] val sampler = Sampler(sampleRate.toFloat)

  private[this] val cache = CacheBuilder.newBuilder()
    .maximumSize(capacity)
    .build(new CacheLoader[SpanId, ConcurrentLinkedQueue[Record]] {
      override def load(key: SpanId) = new ConcurrentLinkedQueue[Record]()
    })

  override def record(record: Record): Unit = {
    val traceId = record.traceId.traceId
    if (sampler(traceId.toLong) && acceptedAnnotations(record.annotation)) {
      val _ = cache.get(traceId).add(record)
    }
  }

  def getRequests: Seq[RequestMetadata] =
    cache.asMap().asScala.values.map(_.asScala).flatMap(mkRequestMetadata).toSeq.sortBy { req =>
      -req.timestamp.inMillis
    }

  private[this] val acceptedAnnotations: Annotation => Boolean = {
    case Annotation.ServerAddr(_) => true
    case Annotation.ClientAddr(_) => true
    case Annotation.BinaryAnnotation("router.label", _) => true
    case Annotation.BinaryAnnotation("service", _) => true
    case Annotation.BinaryAnnotation("client", _) => true
    case _ => false
  }

  private[this] def annotationNamed(annotations: Iterable[Annotation], name: String): Option[String] =
    annotations.collectFirst {
      case Annotation.BinaryAnnotation(n, r) if n == name => r.toString
    }

  /**
   * Given all of the Records for a single traceId, compute metadata for each request.  A request
   * traveling through linkerd consists of two spans: a parent span when the request enters linkerd
   * and a child span when the request leaves linkerd.  Most of the relevant request metadata is
   * computed from records on the parent span but destination address is a record on the child
   * span.  A single trace may pass through linkerd multiple times therefore this method may
   * return multiple RequestMetadata objects.
   */
  private[this] def mkRequestMetadata(records: Iterable[Record]): Seq[RequestMetadata] = {

    // All server addr annontations on this entire trace.  We will need to search this for the
    // child span of a request to find the destination address.
    val allServerAddrs = records.collect {
      case Record(id, _, Annotation.ServerAddr(addr), _) => id -> addr
    }

    // Attempt to generate a RequestMetadata for each span
    records.groupBy(_.traceId.spanId).flatMap {
      case (span, recs) =>

        val clientAddr = recs.collectFirst {
          case Record(id, _, Annotation.ClientAddr(addr), _) => id -> addr
        }
        val serverAddr = recs.collectFirst {
          case Record(id, _, Annotation.ServerAddr(addr), _) => id -> addr
        }
        // Find a server addr annotation on a child span of this span
        val destAddr = allServerAddrs.find {
          case (id, _) => id.parentId == span && id.spanId != span
        }

        val annotations = recs.map(_.annotation)

        // We only produce a RequestMetadata if all of the required annotations can be found
        for {
          (sourceId, src) <- clientAddr
          (serverId, srv) <- serverAddr
          (destinationId, dst) <- destAddr
          router <- annotationNamed(annotations, "router.label")
          logicalName <- annotationNamed(annotations, "service")
          concreteName <- annotationNamed(annotations, "client")
        } yield {
          val timestamp = recs.map(_.timestamp).minBy(_.inMillis)
          RequestMetadata(timestamp, src.toString, srv.toString, router, logicalName, concreteName, dst.toString)
        }
    }.toSeq
  }

  override def sampleTrace(traceId: TraceId): Option[Boolean] = None
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
