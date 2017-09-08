package io.buoyant.k8s.istio.mixer

import com.twitter.finagle.Service
import com.twitter.finagle.buoyant.h2
import com.twitter.logging.Logger
import com.twitter.util.{Future, Return, Throw}
import io.buoyant.grpc.runtime.GrpcStatus.Unknown
import io.buoyant.grpc.runtime.{GrpcStatus, Stream}
import io.buoyant.k8s.istio._
import istio.mixer.v1.{CheckResponse, Mixer}

case class MixerCheckStatus(grpcStatus: GrpcStatus) {
  def success = grpcStatus match {
    case _: GrpcStatus.Ok => true
    case _ => false
  }
}

class MixerClient(client: Mixer) {
  private[this] val log = Logger(this.getClass.getSimpleName)
  private[this] val codeUsedWhenUnknownStatus = Unknown("")

  //TODO: doc
  // minimum set of attributes to generate the following metrics in mixer/prometheus:
  // - request_count
  // - request_duration_bucket
  // - request_duration_count
  // - request_duration_sum
  //
  // example metrics exposed by mixer/prometheus:
  // request_count{method="/productpage",response_code="200",service="productpage",source="unknown",target="productpage.default.svc.cluster.local",version="v1"} 327
  // request_count{method="/reviews",response_code="500",service="reviews",source="productpage",target="reviews.default.svc.cluster.local",version="v1"} 1
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

  //TODO: doc
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
    log.error(e, "Error checking Mixer pre-conditions");
    Future.value(MixerCheckStatus(codeUsedWhenUnknownStatus))
  }

  private[this] def mkStatus(stream: Stream.Releasable[CheckResponse]) = {
    val result = stream.value.`result`.get
    val message = result.`message`.getOrElse("")
    val code = result.`code`.getOrElse(codeUsedWhenUnknownStatus.code)
    val statusCode = GrpcStatus(code, message)
    Future.value(MixerCheckStatus(statusCode))
  }
}

object MixerClient {
  def apply(service: Service[h2.Request, h2.Response]) = new MixerClient(new Mixer.Client(service))
}