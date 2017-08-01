package io.buoyant.router.h2

import com.twitter.finagle.buoyant.h2.{Request, Response, param => h2Param}
import com.twitter.finagle.service.Retries
import com.twitter.finagle.{ServiceFactory, Stack, Stackable, param}
import io.buoyant.router
import io.buoyant.router.ClassifiedRetries.Backoffs

object ClassifiedRetries {
  val role = router.ClassifiedRetries.role

  case class BufferSize(requestBufferSize: Long, responseBufferSize: Long)
  implicit object BufferSize extends Stack.Param[BufferSize] {
    override def default =
      BufferSize(ClassifiedRetryFilter.DefaultBufferSize, ClassifiedRetryFilter.DefaultBufferSize)
  }

  /**
   * A stack module that installs a RetryFilter that uses the stack's
   * ResponseClassifier.
   */
  def module: Stackable[ServiceFactory[Request, Response]] = {
    new Stack.Module6[Backoffs, h2Param.H2Classifier, Retries.Budget, BufferSize, param.HighResTimer, param.Stats, ServiceFactory[Request, Response]] {
      val role = ClassifiedRetries.role
      val description = "Retries requests that are classified to be retryable"
      def make(
        _backoffs: Backoffs,
        _classifier: h2Param.H2Classifier,
        _budget: Retries.Budget,
        _bufferSize: BufferSize,
        _timer: param.HighResTimer,
        _stats: param.Stats,
        next: ServiceFactory[Request, Response]
      ): ServiceFactory[Request, Response] = {
        val Backoffs(backoff) = _backoffs
        val h2Param.H2Classifier(classifier) = _classifier
        val Retries.Budget(budget, _) = _budget
        implicit val param.HighResTimer(timer) = _timer
        val param.Stats(stats) = _stats
        val filter = new ClassifiedRetryFilter(
          stats,
          classifier,
          backoff,
          budget,
          _bufferSize.requestBufferSize,
          _bufferSize.responseBufferSize
        )
        filter andThen next
      }
    }
  }
}
