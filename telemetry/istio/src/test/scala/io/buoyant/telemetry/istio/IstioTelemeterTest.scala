package io.buoyant.telemetry.istio

import com.twitter.conversions.time._
import com.twitter.util.{Time, MockTimer}
import org.scalatest._

class IstioTelemeterTest extends FunSuite {

  test("creates a telemeter") {
    val client = new MockMixerClient
    val telemeter = new IstioTelemeter(
      client,
      new MockTimer
    )

    assert(client.applies == 0)
  }

  test("calls apply on every period until close") {
    val timer = new MockTimer
    val client = new MockMixerClient

    val telemeter = new IstioTelemeter(
      client,
      timer
    )

    Time.withCurrentTimeFrozen { time =>
      val closable = telemeter.run()

      assert(client.applies == 0)

      time.advance(2000.millis)
      timer.tick()
      assert(client.applies == 1)

      time.advance(2000.millis)
      timer.tick()
      assert(client.applies == 2)

      val _ = closable.close(0.millis)

      time.advance(2000.millis)
      timer.tick()
      assert(client.applies == 2)
    }
  }
}
