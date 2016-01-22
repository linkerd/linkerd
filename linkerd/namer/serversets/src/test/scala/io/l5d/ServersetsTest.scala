package io.l5d

import com.twitter.finagle.Stack
import io.buoyant.linkerd.Yaml
import io.l5d.serversets.ZkConnectString
import org.scalatest.FunSuite

class ServersetsTest extends FunSuite {

  def parse(yaml: String) = serversets.parser.read("zkAddrs", Yaml(yaml), Stack.Params.empty)[ZkConnectString]

  test("zkHost list") {
    val yaml = """
- host: foo
  port: 2181
- host: bar
  port: 2182
"""
    assert(parse(yaml) == ZkConnectString("foo:2181,bar:2182"))
  }

  test("single zkHost") {
    val yaml = """
- host: foo
  port: 2181
"""
    assert(parse(yaml) == ZkConnectString("foo:2181"))
  }

  test("missing hostname") {
    val yaml = """
- port: 2181
"""
    intercept[IllegalArgumentException] { parse(yaml) }
  }

  test("default port") {
    val yaml = """
  - host: foo
"""
    assert(parse(yaml) == ZkConnectString("foo:2181"))
  }
}
