package io.buoyant.telemetry

import com.twitter.finagle.Codec
import com.twitter.finagle.stats.{DefaultStatsReceiver, NullStatsReceiver, StatsReceiver}
import com.twitter.finagle.tracing.{Tracer, TraceId, Record}
import com.twitter.finagle.Stack
import com.twitter.finagle.util.DefaultTimer
import com.twitter.finagle.zipkin.core.{DeadlineSpanMap, RawZipkinTracer, Sampler, SamplingTracer, TracerCache, Span}
import com.twitter.logging.Logger
import com.twitter.util._
import java.util.concurrent.ConcurrentHashMap
import java.util.{Map, HashMap}
import org.apache.kafka.clients.producer.{ProducerRecord, ProducerConfig, Callback, RecordMetadata, KafkaProducer}
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.thrift.TSerializer
import scala.collection.JavaConverters._

class KafkaTelemeterInitializer extends TelemeterInitializer {
  type Config = KafkaTelemeterConfig
  override def configId = "io.l5d.zipkinKafka"
  def configClass = classOf[KafkaTelemeterConfig]
}

case class KafkaTelemeterConfig(brokerList: String, sampleRate: Float, numRetries: Int,
  metadataFetchTimeoutMs: Int = 3000, timeoutMs: Int = 300, retryBackoffMs: Int = 10000,
  reconnectBackOffMs: Int = 10000, lingerMs: Int = 1) extends TelemeterConfig {
  private[this] val log = Logger.get(getClass)
  def mk(params: Stack.Params): KafkaTelemeter = {
    log.info("kafka brokers=%s retries=%s sampleRate=%s", brokerList, numRetries, sampleRate)
    new KafkaTelemeter("zipkin", numRetries, sampleRate, brokerList, metadataFetchTimeoutMs, timeoutMs, retryBackoffMs,
      reconnectBackOffMs, lingerMs)
  }
}

/**
 * Going with hybrid approach. Let kafka handle retries. Batching will be handled outside as it is being done today.
 * We will leave linger.ms unchanged for this reason at 1 ms.
 * The reason for this is that the API has been constructed in such a way that a given trace is annotated across multiple
 * different calls rather than gathering up all its annotations and then sending it on its way.
 */
case class KafkaTelemeter(topic: String, numRetries: Int, sampleRate: Float, brokerList: String, metadataFetchTimeoutMs: Int,
  timeoutMs: Int, retryBackoffMs: Int, reconnectBackoffMs: Int, lingerMs: Int) extends Telemeter {
  private[this] val log = Logger.get(getClass)
  log.debug("Initializing kafka telemeter")
  private[this] case class TracerTuple(kafkaRawZipkinTracer: KafkaRawZipkinTracer, kafkaTracer: KafkaTracer)
  private[this] val createTracer = new java.util.function.Function[String, TracerTuple] {
    override def apply(t: String): TracerTuple = {
      val underlyingTracer = new KafkaRawZipkinTracer(brokerList, numRetries, topic)
      new TracerTuple(underlyingTracer, new KafkaTracer(underlyingTracer, sampleRate))
    }
  }

  lazy val tracer: Tracer =
    new KafkaTracer(new KafkaRawZipkinTracer(brokerList, numRetries, topic, metadataFetchTimeoutMs, timeoutMs, retryBackoffMs,
      reconnectBackoffMs, lingerMs), sampleRate)

  private[this] def cleanup(): Unit = {

  }

  class KafkaTracer(tracer: KafkaRawZipkinTracer, sampleRate: Float = Sampler.DefaultSampleRate)
    extends SamplingTracer(tracer, sampleRate) {
    def getTracer(): KafkaRawZipkinTracer = tracer
  }

  override def stats: StatsReceiver = NullStatsReceiver

  override def run(): Closable with Awaitable[Unit] = new Closable with CloseAwaitably {
    def close(deadline: Time): Future[Unit] = {
      closeAwaitably(Future({
        log.debug("Starting cleanup of the kafka telemeter")
        //KafkaRawZipkinTracer.tracerCache.get(brokerList + topic).kafkaRawZipkinTracer.cleanup()
        tracer.asInstanceOf[KafkaTracer].getTracer().cleanup()
        log.debug("Done cleaning up the kafka telemeter")
      }))
    }
  }
}

//Only support thrift for now.
class KafkaRawZipkinTracer(
  brokerList: String,
  numRetries: Int = 3, //this is the kafka default.
  topic: String = "zipkin",
  /* We do not want to wait too long on the broker for the event propagated to the replicas */
  metadataFetchTimeoutMs: Int = 3000,
  timeoutMs: Int = 300,
  retryBackoffMs: Int = 10000,
  reconnectBackoffMs: Int = 10000,
  //Set this to 1 millisecond so that there is enough time for a batch of spans to be pushed to kafka local buffers.
  lingerMs: Int = 1,
  timer: Timer = DefaultTimer.twitter,
  statsReceiver: StatsReceiver = DefaultStatsReceiver
) extends RawZipkinTracer(statsReceiver, timer) {
  private[this] val log = Logger.get(getClass)
  log.info("Initializing kafka zipkin tracer")
  private[this] val producer: KafkaProducer[Array[Byte], Array[Byte]] = {
    val configs: java.util.Map[String, AnyRef] = new java.util.HashMap[String, AnyRef]
    configs.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokerList)
    configs.put(ProducerConfig.METADATA_FETCH_TIMEOUT_CONFIG, metadataFetchTimeoutMs.asInstanceOf[AnyRef])
    configs.put(ProducerConfig.TIMEOUT_CONFIG, timeoutMs.asInstanceOf[AnyRef])
    configs.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, retryBackoffMs.asInstanceOf[AnyRef])
    configs.put(ProducerConfig.RECONNECT_BACKOFF_MS_CONFIG, reconnectBackoffMs.asInstanceOf[AnyRef])
    configs.put(ProducerConfig.RETRIES_CONFIG, numRetries.asInstanceOf[AnyRef])
    configs.put(ProducerConfig.LINGER_MS_CONFIG, lingerMs.asInstanceOf[AnyRef])
    new KafkaProducer[Array[Byte], Array[Byte]](configs, new ByteArraySerializer(), new ByteArraySerializer())
  }

  //retries configured from the input.
  private[this] val scopedReceiver = statsReceiver.scope("log_span")
  private[this] val okCounter = scopedReceiver.counter("ok")
  private[this] val errorReceiver = scopedReceiver.scope("error")

  private[this] def serializeSpan(span: Span): Array[Byte] = {
    log.debug("Serializing span data to bytes")
    val tSerializer = new TSerializer()
    tSerializer.serialize(span.toThrift)
  }

  // Delegate batching to downstream for simplicity reasons rather than doing buffer pools here.
  // The one complication it adds is it is hard to do retries. Breaking it into the model if any one stat emission
  // fails the entire batch is considered to be failed. The batch will only fail if the retries for all the different
  // stats emitted as part of the batch are exhausted independently of each other
  // without being able to send it out successfully.
  // Will try to do early detection. Currently this will enable both
  // kafka level and higher level retries if the framework supports it.
  override def sendSpans(spans: Seq[Span]): Future[Unit] = {
    log.debug("Sending kafka %i trace events", spans.size)
    val futures = spans
      .map(span => new ProducerRecord[Array[Byte], Array[Byte]](topic, null, serializeSpan(span)))
      .map(sendRecord)
      .toStream

    Future.join(futures)
  }

  private[this] def sendRecord(record: ProducerRecord[Array[Byte], Array[Byte]]): Future[Unit] = {
    val promise = new Promise[Unit]
    producer.send(record, new Callback() {
      override def onCompletion(metadata: RecordMetadata, exception: Exception) {
        if (exception != null) {
          val _ = promise.setException(exception)
        } else {
          val _ = promise.setDone()
        }
      }
    })
    promise
  }

  def cleanup() = {
    //Take all the pending spans and flush them.
    Future {
      Await.result(this.flush())
      producer.close()
    }
  }
}

