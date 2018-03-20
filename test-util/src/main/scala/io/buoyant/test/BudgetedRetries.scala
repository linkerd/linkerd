package io.buoyant.test

import org.scalatest.{Canceled, Failed, Outcome, Retries}

/**
 * Mixin trait for tests to support a retry budget.
 */
trait BudgetedRetries extends FunSuite with Retries {

  /**
   * The number of retries permitted before a test is failed.
   *
   * Tests that mix in `BudgetedRetries`
   */
  def retries = 4

  override def withFixture(test: NoArgTest) =
    if (isRetryable(test)) withRetries(test, retries)
    else super.withFixture(test)

  private[this] def withRetries (test: NoArgTest, remaining: Int): Outcome =
    super.withFixture(test) match {
      case Failed(_) | Canceled(_) if remaining == 1 => super.withFixture(test)
      case Failed(_) | Canceled(_) => withRetries(test, remaining - 1)
      case other => other
    }
}
