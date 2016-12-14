package io.buoyant.linkerd.failureAccrual

import com.twitter.finagle.service.exp.FailureAccrualPolicy
import com.twitter.finagle.util.LoadService
import io.buoyant.linkerd.{FailureAccrualConfig, FailureAccrualInitializer}
import io.buoyant.test.FunSuite

class ConsecutiveFailuresTest extends FunSuite {
  test("sanity") {
    val failures = 2
    val config = ConsecutiveFailuresConfig(failures)
    assert(config.policy().isInstanceOf[FailureAccrualPolicy])
    assert(config.failures == failures)
    assert(config.backoff.map(_.mk) == Some(FailureAccrualConfig.defaultBackoff))
  }

  test("service registration") {
    assert(LoadService[FailureAccrualInitializer].exists(_.isInstanceOf[ConsecutiveFailuresInitializer]))
  }
}
