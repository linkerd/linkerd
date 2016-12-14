package io.buoyant.linkerd.failureAccrual

import com.twitter.finagle.service.exp.FailureAccrualPolicy
import com.twitter.finagle.util.LoadService
import io.buoyant.linkerd.{ConstantBackoffConfig, FailureAccrualInitializer}
import io.buoyant.test.FunSuite

class ConsecutiveFailuresTest extends FunSuite {
  test("sanity") {
    val failures = 2
    val backoff = ConstantBackoffConfig(5000)
    val config = ConsecutiveFailuresConfig(failures, Some(backoff))
    assert(config.policy().isInstanceOf[FailureAccrualPolicy])
  }

  test("service registration") {
    assert(LoadService[FailureAccrualInitializer].exists(_.isInstanceOf[ConsecutiveFailuresInitializer]))
  }
}
