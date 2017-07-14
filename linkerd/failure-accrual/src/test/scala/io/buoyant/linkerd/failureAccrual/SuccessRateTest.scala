package io.buoyant.linkerd.failureAccrual

import com.twitter.finagle.liveness.FailureAccrualPolicy
import com.twitter.finagle.util.LoadService
import io.buoyant.linkerd.FailureAccrualInitializer
import io.buoyant.test.FunSuite

class SuccessRateTest extends FunSuite {
  test("sanity") {
    val successRate = 0.6
    val requests = 3
    val config = SuccessRateConfig(successRate, requests)
    assert(config.policy().isInstanceOf[FailureAccrualPolicy])
    assert(config.successRate == successRate)
    assert(config.requests == requests)
  }

  test("service registration") {
    assert(LoadService[FailureAccrualInitializer].exists(_.isInstanceOf[SuccessRateInitializer]))
  }
}
