package io.buoyant.linkerd.failureAccrual

import com.twitter.finagle.service.exp.FailureAccrualPolicy
import com.twitter.finagle.util.LoadService
import io.buoyant.linkerd.{ConstantBackoffConfig, FailureAccrualInitializer}
import io.buoyant.test.FunSuite

class SuccessRateWindowedTest extends FunSuite {
  test("sanity") {
    val sr = 0.4
    val window = 3
    val backoff = ConstantBackoffConfig(5000)
    val config = SuccessRateWindowedConfig(sr, window, Some(backoff))
    assert(config.policy().isInstanceOf[FailureAccrualPolicy])
  }

  test("service registration") {
    assert(LoadService[FailureAccrualInitializer].exists(_.isInstanceOf[SuccessRateWindowedInitializer]))
  }
}
