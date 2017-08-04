package io.buoyant.router.h2

import com.twitter.conversions.time._
import com.twitter.finagle.buoyant.h2.{Request, Response, param => h2Param}
import com.twitter.finagle.service.Retries
import com.twitter.finagle.{ServiceFactory, Stack, Stackable, param}
import com.twitter.util.Duration
import io.buoyant.router
import io.buoyant.router.ClassifiedRetries.Backoffs

object ClassifiedRetries {
  val role = router.ClassifiedRetries.role

  case class BufferSize(requestBufferSize: Long, responseBufferSize: Long)
  implicit object BufferSize extends Stack.Param[BufferSize] {
    override def default =
      BufferSize(ClassifiedRetryFilter.DefaultBufferSize, ClassifiedRetryFilter.DefaultBufferSize)
  }

  case class ClassificationTimeout(timeout: Duration)
  implicit object ClassificationTimeout extends Stack.Param[ClassificationTimeout] {
    override def default = ClassificationTimeout(100.millis)
  }

  /**
   * A stack module that installs a RetryFilter that uses the stack's
   * ResponseClassifier.
   */
  def module: Stackable[ServiceFactory[Request, Response]] = {
    new Stack.Module[ServiceFactory[Request, Response]] {
      val role = ClassifiedRetries.role
      val description = "Retries requests that are classified to be retryable"

      override def parameters: Seq[Stack.Param[_]] = Seq(
        implicitly[Stack.Param[Backoffs]],
        implicitly[Stack.Param[h2Param.H2Classifier]],
        implicitly[Stack.Param[Retries.Budget]],
        implicitly[Stack.Param[ClassificationTimeout]],
        implicitly[Stack.Param[BufferSize]],
        implicitly[Stack.Param[param.HighResTimer]],
        implicitly[Stack.Param[param.Stats]]
      )

      def make(
        params: Stack.Params,
        next: Stack[ServiceFactory[Request, Response]]
      ): Stack[ServiceFactory[Request, Response]] = {

        val filter = new ClassifiedRetryFilter(
          params[param.Stats].statsReceiver,
          params[h2Param.H2Classifier].classifier,
          params[Backoffs].backoff,
          params[Retries.Budget].retryBudget,
          params[ClassificationTimeout].timeout,
          params[BufferSize].requestBufferSize,
          params[BufferSize].responseBufferSize
        )(params[param.HighResTimer].timer)
        Stack.Leaf(role, filter.andThen(next.make(params)))
      }
    }
  }
}
