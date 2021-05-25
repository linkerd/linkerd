package io.buoyant.router

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.twitter.conversions.DurationOps._
import com.twitter.finagle.{ServiceFactory, Stack}
import com.twitter.finagle.service.{Retries, RetryBudget}
import com.twitter.finagle.Backoff
import com.twitter.util.Duration

object RetryBudgetModule {

  val Role = Stack.Role("RetryBudget")

  def module[Req, Rsp] = new Stack.Module[ServiceFactory[Req, Rsp]] {
    override val role = RetryBudgetModule.Role
    override val description = "Instantiate a new retry budget"
    override val parameters = Seq(implicitly[Stack.Param[RetryBudgetConfig]])

    def make(params: Stack.Params, next: Stack[ServiceFactory[Req, Rsp]]): Stack[ServiceFactory[Req, Rsp]] = {
      // The backoff here is a _requeue_ backoff and not used by the path stack.
      // See ClassifieredRetries.Backoffs for the backoff that is actually used in
      // the path stack.
      val budget = Retries.Budget(mkBudget(params[RetryBudgetConfig]), Backoff.constant(Duration.Zero))
      Stack.leaf(role, next.make(params + budget))
    }
  }

  implicit val param: Stack.Param[RetryBudgetConfig] = Stack.Param(RetryBudgetConfig())

  private def mkBudget(config: RetryBudgetConfig): RetryBudget = {
    val ttl = config.ttlSecs.map(_.seconds).getOrElse(10.seconds)
    RetryBudget(ttl, config.minRetriesPerSec.getOrElse(10), config.percentCanRetry.getOrElse(0.2))
  }
}

case class RetryBudgetConfig(
  ttlSecs: Option[Int] = None,
  minRetriesPerSec: Option[Int] = None,
  @JsonDeserialize(contentAs = classOf[java.lang.Double]) percentCanRetry: Option[Double] = None
)
