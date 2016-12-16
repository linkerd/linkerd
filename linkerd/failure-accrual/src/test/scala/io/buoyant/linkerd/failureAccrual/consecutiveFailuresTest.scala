package io.buoyant.linkerd.failureAccrual

import com.twitter.finagle.service.exp.FailureAccrualPolicy
import com.twitter.finagle.util.LoadService
import io.buoyant.linkerd.FailureAccrualInitializer
import io.buoyant.test.FunSuite

class ConsecutiveFailuresTest extends FunSuite {
  test("sanity") {
    val failures = 2
    val config = ConsecutiveFailuresConfig(failures)
    assert(config.policy().isInstanceOf[FailureAccrualPolicy])
    assert(config.failures == failures)
  }

  test("service registration") {
    assert(LoadService[FailureAccrualInitializer].exists(_.isInstanceOf[ConsecutiveFailuresInitializer]))
  }
}
