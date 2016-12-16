package io.buoyant.linkerd.failureAccrual

import com.twitter.finagle.service.exp.FailureAccrualPolicy
import com.twitter.finagle.util.LoadService
import io.buoyant.linkerd.FailureAccrualInitializer
import io.buoyant.test.FunSuite

class SuccessRateWindowedTest extends FunSuite {
  test("sanity") {
    val successRate = 0.4
    val window = 3
    val config = SuccessRateWindowedConfig(successRate, window)
    assert(config.policy().isInstanceOf[FailureAccrualPolicy])
    assert(config.successRate == successRate)
    assert(config.window == window)
  }

  test("service registration") {
    assert(LoadService[FailureAccrualInitializer].exists(_.isInstanceOf[SuccessRateWindowedInitializer]))
  }
}
