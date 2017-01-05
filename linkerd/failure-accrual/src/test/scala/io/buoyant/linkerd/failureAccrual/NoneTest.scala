package io.buoyant.linkerd.failureAccrual

import com.twitter.finagle.service.exp.FailureAccrualPolicy
import com.twitter.finagle.util.LoadService
import io.buoyant.linkerd.FailureAccrualInitializer
import io.buoyant.test.FunSuite

class NoneTest extends FunSuite {
  test("sanity") {
    val config = new NoneConfig
    assert(config.policy().isInstanceOf[FailureAccrualPolicy])
  }

  test("service registration") {
    assert(LoadService[FailureAccrualInitializer].exists(_.isInstanceOf[NoneInitializer]))
  }
}
