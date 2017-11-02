package io.buoyant.k8s.istio.mixer

import com.twitter.finagle.Service
import com.twitter.finagle.buoyant.h2
import com.twitter.logging.Logger
import com.twitter.util.{Future, Return, Throw}
import io.buoyant.grpc.runtime.GrpcStatus._
import io.buoyant.grpc.runtime.{GrpcStatus, Stream}
import io.buoyant.k8s.istio._
import istio.mixer.v1.{CheckResponse, Mixer}

case class MixerCheckStatus(grpcStatus: GrpcStatus) {
  def success = grpcStatus match {
    case _: GrpcStatus.Ok => true
    case _ => false
  }

  def code = grpcStatus.code

  def reason = s"${grpcStatus.getClass.getSimpleName} - ${grpcStatus.code} - ${grpcStatus.message}"

  /**
   * Converts between Mixer's status codes and HTTP status codes, as per [[https://github.com/istio/proxy/blob/7b85ad9cdfa460e928dd24b6c779b1b0e82fff4f/src/envoy/mixer/http_filter.cc#L66 Istio's default proxy]]
   */
  def httpCode = grpcStatus match {
    case _: Ok => 200
    case _: Canceled => 499
    case _: Unknown => 500
    case _: InvalidArgument => 400
    case _: DeadlineExceeded => 504
    case _: NotFound => 404
    case _: AlreadyExists => 409
    case _: PermissionDenied => 403
    case _: ResourceExhausted => 429
    case _: FailedPrecondition => 400
    case _: Aborted => 409
    case _: OutOfRange => 400
    case _: Unimplemented => 501
    case _: Internal => 500
    case _: Unavailable => 503
    case _: DataLoss => 500
    case _: Unauthenticated => 401
    case _: Other => 500
  }
}

class MixerClient(client: Mixer) {
  private[this] val log = Logger(this.getClass.getSimpleName)
  private[this] val codeUsedWhenUnknownStatus = Unknown("NO RESPONSE FROM SERVER")

  /**
   * Sends a report to Istio' Mixer, using the [[https://istio.io/docs/reference/api/mixer/mixer-service.html#report Report API]]
   *
   * minimum set of attributes to generate the following metrics in mixer/prometheus:
   * - request_count
   * - request_duration_bucket
   * - request_duration_count
   * - request_duration_sum
   * *
   * example metrics exposed by mixer/prometheus:
   * request_count{method="/productpage",response_code="200",service="productpage",source="unknown",target="productpage.default.svc.cluster.local",version="v1"} 327
   * request_count{method="/reviews",response_code="500",service="reviews",source="productpage",target="reviews.default.svc.cluster.local",version="v1"} 1
   */
  def report(
    responseCode: ResponseCodeIstioAttribute,
    requestPath: RequestPathIstioAttribute,
    targetService: TargetServiceIstioAttribute,
    sourceLabel: SourceLabelIstioAttribute,
    targetLabel: TargetLabelsIstioAttribute,
    duration: ResponseDurationIstioAttribute
  ): Future[Unit] = {

    val reportRequest = MixerApiRequests.mkReportRequest(
      responseCode,
      requestPath,
      targetService,
      sourceLabel,
      targetLabel,
      duration
    )
    log.trace("MixerClient.report: %s", reportRequest)
    client.report(Stream.value(reportRequest))
    Future.Done
  }

  /**
   * Checks this request against Mixer's Pre-Condition system, using the [[https://istio.io/docs/reference/api/mixer/mixer-service.html#check Check API]]
   *
   * @param istioRequest
   * @return
   */
  def checkPreconditions(istioRequest: IstioRequest[_]): Future[MixerCheckStatus] = {
    val checkRequest = MixerApiRequests.mkCheckRequest(istioRequest)
    log.trace("MixerClient.check: %s", checkRequest)

    client.check(Stream.value(checkRequest)).recv().transform {
      case Return(stream) => mkStatus(stream)
      case Throw(e) => handleExceptionInPreconditionCheck(e)
    }
  }

  private def handleExceptionInPreconditionCheck(e: Throwable) = {
    //TODO: Consider making this behaviour configurable, as per https://github.com/istio/mixerclient/blob/13460a96b4b3aa792ad47817d4fbf529b63c25e2/src/check_cache.cc#L163
    log.error("Error checking Mixer pre-conditions: %s - %s", e.getClass, e.getMessage);
    Future.value(MixerCheckStatus(codeUsedWhenUnknownStatus))
  }

  private[this] def mkStatus(streamItem: Stream.Releasable[CheckResponse]) = {
    val result = streamItem.value.`result`.get
    val message = result.`message`.getOrElse("No message")
    val code = result.`code`.getOrElse(0)
    val statusCode = GrpcStatus(code, message)
    Future.value(MixerCheckStatus(statusCode))
  }
}

object MixerClient {
  def apply(service: Service[h2.Request, h2.Response]) = new MixerClient(new Mixer.Client(service))
}